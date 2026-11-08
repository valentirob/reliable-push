package io.reliablepush.harness.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.Notification;
import io.reliablepush.client.NotificationState;
import io.reliablepush.client.ReliablePushException;
import io.reliablepush.client.SenderClient;
import io.reliablepush.harness.config.HarnessApp;
import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.devices.DeviceSimulator;
import io.reliablepush.harness.devices.GrantMinter;
import io.reliablepush.harness.logs.BackendLogTail;
import io.reliablepush.harness.receiver.WebhookInbox;
import io.reliablepush.harness.sender.FakeSender;
import io.reliablepush.harness.sender.SendPushCommand;
import io.reliablepush.harness.tracking.Timeline;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The context helpers scenarios rely on, against a mocked sender API. */
class ScenarioContextTest {

    @TempDir
    Path logDir;

    final FakeSender sender = mock(FakeSender.class);
    final SenderClient client = mock(SenderClient.class);
    final UUID id = UUID.randomUUID();
    final WebhookInbox inbox = spy(new WebhookInbox());
    ScenarioRunner runner;

    static Notification n(UUID id, NotificationState state) {
        return new Notification(id, state, "u", null, FallbackPolicy.NOT_DELIVERED, 5, Instant.now(), null, null, null,
                null);
    }

    static FakeSender.SendResult registered(UUID id) {
        return new FakeSender.SendResult(id, n(id, NotificationState.PENDING), null, false);
    }

    @BeforeEach
    void setUp() {
        when(sender.senderClient("shop")).thenReturn(client);
        HarnessProperties props = new HarnessProperties(URI.create("http://127.0.0.1:1"), logDir, Map.of(
                "shop", new HarnessApp("sender-key-0123456789", "grant-secret-0123456789", "hmac-secret-0123456789",
                        "https://shop.test.invalid")));
        var components = new ScenarioContext.Components(sender, mock(DeviceSimulator.class), new GrantMinter(props),
                inbox, new Timeline(), new BackendLogTail(logDir, Duration.ofHours(1)), props);
        runner = new ScenarioRunner(List.of(), components, Duration.ofSeconds(10), Duration.ofMillis(10));
    }

    @AfterEach
    void close() {
        runner.close();
    }

    ScenarioResult run(ScenarioRunnerTest.TestScenario.Body body) {
        ScenarioRunner r = new ScenarioRunner(List.of(new ScenarioRunnerTest.TestScenario("t", false, body)),
                runner.components(), Duration.ofSeconds(10), Duration.ofMillis(10));
        try {
            return r.run("t");
        } finally {
            r.close();
        }
    }

    @Test
    void awaitStatePollsUntilReachedAndToleratesTransientErrors() {
        AtomicInteger calls = new AtomicInteger();
        when(client.get(id)).thenAnswer(inv -> switch (calls.incrementAndGet()) {
            case 1 -> Optional.of(n(id, NotificationState.PENDING));
            case 2 -> throw new ReliablePushException("io", -1, null, null, null);
            default -> Optional.of(n(id, NotificationState.DELIVERED));
        });

        ScenarioResult r = run(ctx -> {
            ctx.track(id, "shop");
            ctx.awaitState(id, NotificationState.DELIVERED, Duration.ofSeconds(2));
        });

        assertThat(r.status()).isEqualTo("PASSED");
        assertThat(calls.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void awaitStateFailsFastOnADifferentFinalState() {
        when(client.get(id)).thenReturn(Optional.of(n(id, NotificationState.FALLBACK_FAILED)));

        long start = System.nanoTime();
        ScenarioResult r = run(ctx -> {
            ctx.track(id, "shop");
            ctx.awaitState(id, NotificationState.FALLBACK_TRIGGERED, Duration.ofSeconds(5));
        });

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(r.failure().expected()).contains("FALLBACK_TRIGGERED");
        assertThat(r.failure().actual()).contains("FALLBACK_FAILED").contains("final");
        assertThat(r.failure().hint()).contains(id.toString());
    }

    @Test
    void awaitStateTimesOutWithLastSeenState() {
        when(client.get(id)).thenReturn(Optional.of(n(id, NotificationState.PENDING)));

        ScenarioResult r = run(ctx -> {
            ctx.track(id, "shop");
            ctx.awaitState(id, NotificationState.DELIVERED, Duration.ofMillis(100));
        });

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.failure().expected()).contains("DELIVERED").contains("100 ms");
        assertThat(r.failure().actual()).contains("PENDING");
    }

    @Test
    void sendRecordsTheAppSoAwaitStateNeedsNoAppId() {
        when(sender.send(any(SendPushCommand.class))).thenReturn(registered(id));
        when(client.get(id)).thenReturn(Optional.of(n(id, NotificationState.PENDING)));

        ScenarioResult r = run(ctx -> {
            UUID sent = ctx.send("shop", ctx.userId(1), FallbackPolicy.NOT_DELIVERED, 5, Map.of());
            ctx.awaitState(sent, NotificationState.PENDING, Duration.ofSeconds(1));
        });

        assertThat(r.status()).isEqualTo("PASSED");
    }

    @Test
    void sendUsesAUniqueFallbackTextThatTheHygieneScanLooksFor() {
        org.mockito.ArgumentCaptor<SendPushCommand> sent = org.mockito.ArgumentCaptor.forClass(SendPushCommand.class);
        when(sender.send(sent.capture())).thenReturn(registered(id));
        when(inbox.signaturesForLogHygieneScan()).thenReturn(List.of("0123456789abcdef"));
        java.util.concurrent.atomic.AtomicReference<List<String>> secrets =
                new java.util.concurrent.atomic.AtomicReference<>();

        ScenarioResult r = run(ctx -> {
            ctx.send("shop", ctx.userId(1), FallbackPolicy.NOT_DELIVERED, 5, Map.of());
            ctx.send("shop", ctx.userId(1), FallbackPolicy.NOT_DELIVERED, 5, Map.of());
            secrets.set(ctx.secrets());
        });

        assertThat(r.status()).isEqualTo("PASSED");
        List<Object> texts = sent.getAllValues().stream().map(c -> (Object) c.fallback().get("text")).toList();
        assertThat(texts).doesNotHaveDuplicates().allSatisfy(t -> assertThat((String) t).hasSizeGreaterThan(8));
        assertThat(sent.getValue().fallback().get("channel")).isEqualTo("sms");
        assertThat(secrets.get()).containsAll(texts.stream().map(String.class::cast).toList())
                .contains("0123456789abcdef");
    }

    @Test
    void sendFailsTheScenarioWhenTheRegistrationFailed() {
        when(sender.send(any(SendPushCommand.class))).thenReturn(new FakeSender.SendResult(id, null, 400, false));

        ScenarioResult r = run(ctx -> ctx.send("shop", ctx.userId(1), FallbackPolicy.NOT_DELIVERED, 5, Map.of()));

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.failure().actual()).contains("400");
    }

    @Test
    void sendWithOptionsReturnsTheResultAndPassesTheOptions() {
        org.mockito.ArgumentCaptor<SendPushCommand> sent = org.mockito.ArgumentCaptor.forClass(SendPushCommand.class);
        when(sender.send(sent.capture())).thenReturn(new FakeSender.SendResult(id, null, null, true));
        java.util.concurrent.atomic.AtomicReference<FakeSender.SendResult> result =
                new java.util.concurrent.atomic.AtomicReference<>();

        ScenarioResult r = run(ctx -> result.set(ctx.send("shop", ctx.userId(1), FallbackPolicy.NOT_DELIVERED, 5,
                Map.of(), id, "ext-1", true)));

        assertThat(r.status()).isEqualTo("PASSED");
        assertThat(result.get().skipped()).isTrue();
        assertThat(sent.getValue().trackingId()).isEqualTo(id);
        assertThat(sent.getValue().externalId()).isEqualTo("ext-1");
        assertThat(sent.getValue().skipRegistration()).isTrue();
    }

    @Test
    void awaitStateFailsFastOnNoDeviceRegistered() {
        when(client.get(id)).thenReturn(Optional.of(n(id, NotificationState.NO_DEVICE_REGISTERED)));

        ScenarioResult r = run(ctx -> {
            ctx.track(id, "shop");
            ctx.awaitState(id, NotificationState.DELIVERED, Duration.ofSeconds(30));
        });

        assertThat(r.failure().actual()).contains("NO_DEVICE_REGISTERED").contains("final");
    }

    @Test
    void untrackedIdIsAProgrammingError() {
        ScenarioResult r = run(ctx -> ctx.awaitState(id, NotificationState.PENDING, Duration.ofSeconds(1)));

        assertThat(r.failure().actual()).startsWith("IllegalArgumentException");
    }

    @Test
    void expectNoWebhookPassesWhenNothingArrived() {
        ScenarioResult r = run(ctx -> {
            ctx.expectNoWebhook(id, Instant.now().plusMillis(50));
            ctx.expectWebhookCount(id, 0);
        });

        assertThat(r.status()).isEqualTo("PASSED");
    }

    @Test
    void expectWebhookCountFailsWithCounts() {
        ScenarioResult r = run(ctx -> ctx.expectWebhookCount(id, 1));

        assertThat(r.failure().expected()).contains("exactly 1 webhook");
        assertThat(r.failure().actual()).contains("0");
    }
}
