package io.reliablepush.harness.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import io.reliablepush.harness.config.HarnessApp;
import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.devices.DeviceSimulator;
import io.reliablepush.harness.devices.GrantMinter;
import io.reliablepush.harness.logs.BackendLogTail;
import io.reliablepush.harness.receiver.ReceiverMode;
import io.reliablepush.harness.receiver.WebhookInbox;
import io.reliablepush.harness.sender.FakeSender;
import io.reliablepush.harness.tracking.Timeline;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioRunnerTest {

    @TempDir
    Path logDir;

    final WebhookInbox inbox = new WebhookInbox();
    ScenarioRunner runner;

    /** A scenario whose body is a lambda. */
    record TestScenario(String name, boolean externalControl, Body body) implements Scenario {
        interface Body {
            void run(ScenarioContext ctx) throws Exception;
        }

        @Override
        public String description() {
            return "test scenario " + name;
        }

        @Override
        public void run(ScenarioContext ctx) throws Exception {
            body.run(ctx);
        }
    }

    /** External scenario: prepare returns a fixed id, verify records what it got. */
    static final class TestExternal implements Scenario.External {
        final String name;
        final UUID id = UUID.randomUUID();
        final AtomicReference<UUID> verified = new AtomicReference<>();
        volatile boolean failVerify;

        TestExternal() {
            this("ext");
        }

        TestExternal(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "external"; }

        @Override
        public UUID prepare(ScenarioContext ctx) {
            ctx.inbox().setMode("shop", ReceiverMode.HANG, Duration.ofMillis(1500));
            ctx.log("prepared");
            return id;
        }

        @Override
        public void verify(ScenarioContext ctx, UUID trackingId) {
            verified.set(trackingId);
            ctx.log("verified");
            if (failVerify) {
                ctx.fail("e", "a", "h");
            }
        }
    }

    GrantMinter minter;

    ScenarioRunner runner(Duration timeout, Scenario... scenarios) {
        return runner(timeout, Duration.ofMinutes(2), scenarios);
    }

    ScenarioRunner runner(Duration timeout, Duration reservation, Scenario... scenarios) {
        HarnessProperties props = new HarnessProperties(URI.create("http://127.0.0.1:1"), logDir, Map.of(
                "shop", new HarnessApp("sender-key-0123456789", "grant-secret-0123456789", "hmac-secret-0123456789",
                        "https://shop.test.invalid")));
        minter = new GrantMinter(props);
        var components = new ScenarioContext.Components(mock(FakeSender.class), mock(DeviceSimulator.class), minter,
                inbox, new Timeline(), new BackendLogTail(logDir, Duration.ofHours(1)), props);
        runner = new ScenarioRunner(List.of(scenarios), components, timeout, Duration.ofMillis(10), reservation);
        return runner;
    }

    @AfterEach
    void close() {
        if (runner != null) {
            runner.close();
        }
    }

    @Test
    void secondConcurrentRunRejected() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScenarioRunner r = runner(Duration.ofSeconds(10), new TestScenario("blocking", false, ctx -> {
            started.countDown();
            release.await();
        }));

        CompletableFuture<List<ScenarioResult>> first = CompletableFuture.supplyAsync(r::runAll);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(r.running()).isTrue();

        assertThatThrownBy(r::runAll).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
        assertThatThrownBy(() -> r.run("blocking")).isInstanceOf(IllegalStateException.class);

        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).singleElement()
                .satisfies(res -> assertThat(res.status()).isEqualTo("PASSED"));
        assertThat(r.running()).isFalse();
        assertThat(r.run("blocking").status()).isEqualTo("PASSED"); // guard released
    }

    @Test
    void timeoutMarksFailed() {
        ScenarioRunner r = runner(Duration.ofMillis(200),
                new TestScenario("sleepy", false, ctx -> Thread.sleep(10_000)));

        long start = System.nanoTime();
        ScenarioResult result = r.run("sleepy");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failure().expected()).contains("200 ms");
        assertThat(result.failure().actual()).contains("timed out");
        assertThat(result.failure().hint()).isNotBlank();
        assertThat(r.lastResults()).containsExactly(result);
    }

    @Test
    void failureCarriesExpectedActualHint() {
        ScenarioRunner r = runner(Duration.ofSeconds(5), new TestScenario("failing", false, ctx -> {
            ctx.log("about to fail");
            ctx.fail("state DELIVERED", "state PENDING", "look at the device simulator");
        }));

        ScenarioResult result = r.run("failing");

        assertThat(result.scenario()).isEqualTo("failing");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failure()).isEqualTo(
                new ScenarioResult.Failure("state DELIVERED", "state PENDING", "look at the device simulator"));
        assertThat(result.logs()).singleElement().asString().endsWith("about to fail");
    }

    @Test
    void unexpectedExceptionIsAFailureWithItsType() {
        ScenarioRunner r = runner(Duration.ofSeconds(5), new TestScenario("boom", false, ctx -> {
            throw new IllegalStateException("device registration failed: HTTP status 503");
        }));

        ScenarioResult result = r.run("boom");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failure().actual())
                .isEqualTo("IllegalStateException: device registration failed: HTTP status 503");
    }

    @Test
    void runAllSkipsExternalScenariosAndKeepsCatalogueOrder() {
        ScenarioRunner r = runner(Duration.ofSeconds(5),
                new TestScenario("b", false, ctx -> { }),
                new TestExternal(),
                new TestScenario("a", false, ctx -> { }));

        assertThat(r.runAll()).extracting(ScenarioResult::scenario).containsExactly("b", "a");
        assertThat(r.run(List.of("ext", "a"))).extracting(ScenarioResult::status).containsExactly("SKIPPED", "PASSED");
    }

    @Test
    void unknownScenarioRejectedBeforeRunning() {
        ScenarioRunner r = runner(Duration.ofSeconds(5), new TestScenario("a", false, ctx -> { }));

        assertThatThrownBy(() -> r.run(List.of("a", "nope")))
                .isInstanceOf(ScenarioRunner.UnknownScenarioException.class).hasMessageContaining("nope");
        assertThat(r.lastResults()).isEmpty();
    }

    @Test
    void receiverResetToOkAfterEachScenarioAndInboxClearedBefore() {
        ScenarioRunner r = runner(Duration.ofSeconds(5),
                new TestScenario("sets-mode", false, ctx -> {
                    ctx.inbox().setMode("shop", ReceiverMode.FAIL_500, Duration.ZERO);
                    ctx.fail("x", "y", "z");
                }),
                new TestScenario("sees-ok", false, ctx -> {
                    if (ctx.inbox().mode("shop").mode() != ReceiverMode.OK) {
                        ctx.fail("receiver OK", ctx.inbox().mode("shop").mode().name(), "runner reset");
                    }
                }));

        assertThat(r.runAll()).extracting(ScenarioResult::status).containsExactly("FAILED", "PASSED");
        assertThat(inbox.mode("shop").mode()).isEqualTo(ReceiverMode.OK);
    }

    @Test
    void userIdsAreUniquePerRunAndScenario() {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        ScenarioRunner r = runner(Duration.ofSeconds(5), new TestScenario("ids", false, ctx -> {
            (first.get() == null ? first : second).set(ctx.userId(1));
            if (!ctx.userId(1).startsWith(ctx.userIdPrefix() + "-ids-")) {
                ctx.fail("prefixed id", ctx.userId(1), "");
            }
        }));

        r.run("ids");
        r.run("ids");

        assertThat(first.get()).endsWith("-ids-1").isNotEqualTo(second.get());
    }

    @Test
    void secretsContainAppSecretsGrantsAndUserIdsOfEarlierRunsToo() {
        AtomicReference<String> firstUser = new AtomicReference<>();
        AtomicReference<List<String>> seen = new AtomicReference<>();
        ScenarioRunner r = runner(Duration.ofSeconds(5), new TestScenario("s", false, ctx -> {
            String user = ctx.userId(7);
            firstUser.compareAndSet(null, user);
            seen.set(ctx.secrets());
        }));

        assertThat(r.run("s").status()).isEqualTo("PASSED");
        String grant = minter.mint("shop", "someone");
        assertThat(r.run("s").status()).isEqualTo("PASSED"); // a later run, new prefix

        assertThat(seen.get()).contains("sender-key-0123456789", "grant-secret-0123456789", "hmac-secret-0123456789",
                grant, firstUser.get());
    }

    @Test
    void preparedExternalReservesTheRunnerUntilVerified() {
        TestExternal ext = new TestExternal("ext");
        ScenarioRunner r = runner(Duration.ofSeconds(5), new TestScenario("a", false, ctx -> { }), ext,
                new TestExternal("ext2"));
        r.prepare("ext");

        assertThatThrownBy(r::runAll).isInstanceOf(ScenarioRunner.RunInProgressException.class)
                .hasMessageContaining("external scenario ext is prepared; verify it first");
        assertThatThrownBy(() -> r.prepare("ext2")).isInstanceOf(ScenarioRunner.RunInProgressException.class);
        assertThat(r.running()).isFalse();
        assertThat(inbox.mode("shop").mode()).isEqualTo(ReceiverMode.HANG);

        assertThat(r.verify("ext", null).status()).isEqualTo("PASSED");
        assertThat(r.runAll()).extracting(ScenarioResult::status).containsExactly("PASSED");
    }

    @Test
    void expiredReservationIsReleasedAndReceiversReset() throws Exception {
        ScenarioRunner r = runner(Duration.ofSeconds(5), Duration.ofMillis(50),
                new TestScenario("a", false, ctx -> {
                    if (ctx.inbox().mode("shop").mode() != ReceiverMode.OK) {
                        ctx.fail("receiver OK", ctx.inbox().mode("shop").mode().name(), "expired reservation reset");
                    }
                }), new TestExternal());
        r.prepare("ext");
        Thread.sleep(150);

        assertThat(r.runAll()).extracting(ScenarioResult::status).containsExactly("PASSED");
        assertThatThrownBy(() -> r.verify("ext", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prepare");
    }

    @Test
    void externalPrepareThenVerifyLandsInLastResults() {
        TestExternal ext = new TestExternal();
        ScenarioRunner r = runner(Duration.ofSeconds(5), new TestScenario("a", false, ctx -> { }), ext);
        r.runAll();

        ScenarioRunner.Prepared prepared = r.prepare("ext");
        assertThat(prepared.trackingId()).isEqualTo(ext.id);
        assertThat(prepared.result()).isNull();
        assertThat(inbox.mode("shop").mode()).isEqualTo(ReceiverMode.HANG); // kept for the external kill

        ScenarioResult verified = r.verify("ext", null);

        assertThat(ext.verified.get()).isEqualTo(ext.id);
        assertThat(verified.status()).isEqualTo("PASSED");
        assertThat(verified.logs()).hasSize(2);
        assertThat(inbox.mode("shop").mode()).isEqualTo(ReceiverMode.OK);
        assertThat(r.lastResults()).extracting(ScenarioResult::scenario).containsExactly("a", "ext");

        ext.failVerify = true;
        r.prepare("ext");
        assertThat(r.verify("ext", ext.id).status()).isEqualTo("FAILED");
        assertThat(r.lastResults()).extracting(ScenarioResult::status).containsExactly("PASSED", "FAILED");
    }

    @Test
    void prepareAndVerifyRejectNonExternalAndVerifyNeedsAnId() {
        ScenarioRunner r = runner(Duration.ofSeconds(5), new TestScenario("a", false, ctx -> { }), new TestExternal());

        assertThatThrownBy(() -> r.prepare("a")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an external-control scenario");
        assertThatThrownBy(() -> r.verify("ext", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prepare");
    }

    @Test
    void runningFlagVisibleDuringScenario() {
        CountDownLatch release = new CountDownLatch(1);
        ScenarioRunner r = runner(Duration.ofSeconds(10), new TestScenario("b", false, ctx -> release.await()));
        CompletableFuture<ScenarioResult> f = CompletableFuture.supplyAsync(() -> r.run("b"));
        await().atMost(Duration.ofSeconds(5)).until(r::running);
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(f::isDone);
    }
}
