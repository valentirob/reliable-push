package io.reliablepush.fallback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.reliablepush.notifications.FallbackPolicy;
import io.reliablepush.notifications.Notification;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.notifications.NotificationState;
import io.reliablepush.notifications.RetentionJob;
import io.reliablepush.shared.time.TimeScale;
import io.reliablepush.support.MutableClock;
import io.reliablepush.support.PostgresTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Every webhook call the fallback worker makes is recorded in {@code fallback_attempt}, in the transaction of the
 * fenced outcome update (ops dashboard spec 3.1). The receiver is WireMock; time comes from a {@link MutableClock} in
 * the year 2100 (rows created here are never due for workers of other cached contexts), 1 product minute = 1 s, and
 * ticks are driven by the tests. Every assertion is scoped to this test's own notification ids.
 */
@ActiveProfiles({"test", "api", "worker", "e2e"})
@TestPropertySource(properties = {
    "reliable-push.testing.minute-duration=1s",
    "reliable-push.worker.poll-interval=1d",
    "reliable-push.apps.fbattempt.sender-api-key-sha256=a2cd39316c98ea096773f14854fd45f790f78b9df744dc4be3555cd2f662ba99",
    "reliable-push.apps.fbattempt.identity.type=oidc",
    "reliable-push.apps.fbattempt.identity.issuer=https://idp.test.example",
    "reliable-push.apps.fbattempt.identity.audience=fbattempt-mobile",
    "reliable-push.apps.fbattempt.fallback.hmac-secret-ref=env:RP_TEST_SHOP_HOOK_SECRET"
})
@Import(FallbackAttemptIT.Fakes.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FallbackAttemptIT extends PostgresTestBase {

    private static final String APP = "fbattempt";
    private static final Instant T0 = Instant.parse("2100-01-01T00:00:00Z");
    private static final WireMockServer RECEIVER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        RECEIVER.start();
    }

    @DynamicPropertySource
    static void receiverUrl(DynamicPropertyRegistry registry) {
        registry.add("reliable-push.apps." + APP + ".fallback.url", () -> "http://localhost:" + RECEIVER.port() + "/hook");
    }

    @AfterAll
    static void stopReceiver() {
        RECEIVER.stop();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    FallbackScheduler scheduler;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    FallbackAttemptRepository attempts;

    @Autowired
    RetentionJob retention;

    @Autowired
    MutableClock clock;

    @Autowired
    TimeScale timeScale;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void freshTime() {
        // every test starts one (real) hour later, so leftovers of earlier tests are long due and never in the way
        clock.advance(Duration.ofHours(1));
        RECEIVER.resetAll();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private UUID insert(Instant dueAt) {
        UUID id = UUID.randomUUID();
        Duration ten = timeScale.minutes(10);
        notifications.insert(new Notification(id, APP, "user-" + id, null, FallbackPolicy.NOT_DELIVERED, 10,
                "{\"sms\":\"x\"}", NotificationState.PENDING, dueAt.minus(ten), dueAt, null, null, null));
        return id;
    }

    private NotificationState state(UUID id) {
        return NotificationState.valueOf(
                jdbc.sql("select state from notification where id = ?").param(id).query(String.class).single());
    }

    private static void assertRow(FallbackAttempt a, int attempt, String outcome, Integer status, String reason) {
        assertThat(a.attempt()).isEqualTo(attempt);
        assertThat(a.outcome()).isEqualTo(outcome);
        assertThat(a.httpStatus()).isEqualTo(status);
        assertThat(a.reason()).isEqualTo(reason);
        assertThat(a.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(a.startedAt()).isEqualTo(a.startedAt().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void successWritesOneSuccessAttempt() {
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
        UUID id = insert(now().minusMillis(1));

        scheduler.tick();

        List<FallbackAttempt> rows = attempts.forNotification(id);
        assertThat(rows).hasSize(1);
        assertRow(rows.get(0), 1, "SUCCESS", 200, null);
        assertThat(rows.get(0).notificationId()).isEqualTo(id);
        assertThat(state(id)).isEqualTo(NotificationState.FALLBACK_TRIGGERED);
    }

    @Test
    void retryThenSuccessWritesTwoRows() {
        RECEIVER.stubFor(post(urlEqualTo("/hook")).inScenario("retry").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        RECEIVER.stubFor(post(urlEqualTo("/hook")).inScenario("retry").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));
        UUID id = insert(now().minusMillis(1));

        scheduler.tick();
        assertThat(attempts.forNotification(id)).hasSize(1);
        clock.advance(timeScale.minutes(1));
        scheduler.tick();

        List<FallbackAttempt> rows = attempts.forNotification(id);
        assertThat(rows).hasSize(2);
        assertRow(rows.get(0), 1, "RETRYABLE", 503, "http_503");
        assertRow(rows.get(1), 2, "SUCCESS", 200, null);
    }

    @Test
    void finalFailureWritesFinal() {
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(400)));
        UUID id = insert(now().minusMillis(1));

        scheduler.tick();

        List<FallbackAttempt> rows = attempts.forNotification(id);
        assertThat(rows).hasSize(1);
        assertRow(rows.get(0), 1, "FINAL", 400, "http_400");
        assertThat(state(id)).isEqualTo(NotificationState.FALLBACK_FAILED);
    }

    @Test
    void expiryAfterACallWritesItsAttempt() {
        // due 19 product minutes ago, window 20: attempt 1 fails, the 1 minute backoff would end after the window
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(503)));
        UUID id = insert(now().minus(timeScale.minutes(20)).plusMillis(500));

        scheduler.tick();

        assertThat(state(id)).isEqualTo(NotificationState.FALLBACK_EXPIRED);
        List<FallbackAttempt> rows = attempts.forNotification(id);
        assertThat(rows).hasSize(1);
        assertRow(rows.get(0), 1, "RETRYABLE", 503, "http_503");
    }

    @Test
    void expiryWithoutCallWritesNothing() {
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
        UUID id = insert(now().minus(timeScale.minutes(21)));

        scheduler.tick();

        assertThat(state(id)).isEqualTo(NotificationState.FALLBACK_EXPIRED);
        assertThat(attempts.forNotification(id)).isEmpty();
    }

    @Test
    void staleHolderWritesNoAttempt() throws Exception {
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200).withFixedDelay(500)));
        UUID id = insert(now().minusMillis(1));

        CompletableFuture<Integer> tick = CompletableFuture.supplyAsync(scheduler::tick);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (state(id) != NotificationState.DISPATCHING && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        // what a re-claim does to the row: the holder's lease no longer matches
        jdbc.sql("update notification set lease_until = lease_until + interval '1 second' where id = ?")
                .param(id).update();
        tick.get(10, TimeUnit.SECONDS);

        assertThat(state(id)).isEqualTo(NotificationState.DISPATCHING);
        assertThat(attempts.forNotification(id)).isEmpty();
    }

    @Test
    void failedAttemptInsertRollsTheOutcomeBack() {
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
        UUID id = insert(now().minusMillis(1));
        // attempt 1 already exists, so the worker's own insert violates fallback_attempt_once
        FallbackAttempt existing = new FallbackAttempt(id, 1, now(), 1, "RETRYABLE", 503, "http_503");
        attempts.insert(existing);

        scheduler.tick();

        assertThat(state(id)).isEqualTo(NotificationState.DISPATCHING);
        assertThat(jdbc.sql("select fallback_attempts from notification where id = ?").param(id)
                        .query(Integer.class).single())
                .isZero();
        assertThat(attempts.forNotification(id)).hasSize(1);
        assertThat(attempts.forNotification(id).get(0).outcome()).isEqualTo("RETRYABLE");
    }

    @Test
    void attemptsGoWithTheNotification() {
        UUID id = UUID.randomUUID();
        Instant closedAt = Instant.parse("2000-01-01T00:00:00Z");
        jdbc.sql("""
                insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state,
                                          created_at, closed_at)
                values (?, ?, 'user-1', 'NOT_DELIVERED', 10, '{}'::jsonb, 'FALLBACK_TRIGGERED', ?, ?)
                """)
                .params(id, APP, closedAt.minusSeconds(60).atOffset(java.time.ZoneOffset.UTC),
                        closedAt.atOffset(java.time.ZoneOffset.UTC))
                .update();
        attempts.insert(new FallbackAttempt(id, 1, closedAt, 5, "SUCCESS", 200, null));
        assertThat(attempts.forNotification(id)).hasSize(1);

        retention.purge();

        assertThat(attempts.forNotification(id)).isEmpty();
    }
}
