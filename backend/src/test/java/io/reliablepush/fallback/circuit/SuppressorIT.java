package io.reliablepush.fallback.circuit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.fallback.FallbackCall;
import io.reliablepush.fallback.FallbackScheduler;
import io.reliablepush.fallback.WebhookClient;
import io.reliablepush.fallback.WebhookResult;
import io.reliablepush.notifications.ClaimedNotification;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.shared.logging.EventCapture;
import io.reliablepush.shared.metrics.RpMetrics;
import io.reliablepush.shared.security.DevicePrincipal;
import io.reliablepush.support.MutableClock;
import io.reliablepush.support.PostgresTestBase;
import io.reliablepush.tracking.DeviceEvent;
import io.reliablepush.tracking.EventBatchResult;
import io.reliablepush.tracking.EventType;
import io.reliablepush.tracking.TrackingService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Suppression of the fallbacks of an open (or suspended) circuit and its place in the worker tick (spec 5), against
 * a real PostgreSQL. Time comes from a {@link MutableClock} in the year 2102 (each circuit IT has its own year): the
 * rows written here are never due for the workers of other cached contexts (real clock). The tick claims globally, so
 * every assertion is scoped to the two apps of this class, whose rows are deleted after each test.
 */
@ActiveProfiles({"api", "worker", "test"})
@TestPropertySource(properties = {
    "reliable-push.worker.poll-interval=1d",
    "reliable-push.fallback.circuit-breaker.enabled=true", // off in the test profile
    "reliable-push.apps.supa.sender-api-key-sha256=4f33fd6c3f941f7f293575fef17ee38112950e87155c322dbea56d9765721ad5",
    "reliable-push.apps.supa.identity.type=oidc",
    "reliable-push.apps.supa.identity.issuer=https://idp.test.example",
    "reliable-push.apps.supa.identity.audience=supa-mobile",
    "reliable-push.apps.supa.fallback.url=http://localhost:9/hook",
    "reliable-push.apps.supa.fallback.hmac-secret-ref=env:RP_TEST_SHOP_HOOK_SECRET",
    "reliable-push.apps.supb.sender-api-key-sha256=762096c08923b1b944b0962577374e476c3c71a838078c4f81801fef014ae0dc",
    "reliable-push.apps.supb.identity.type=oidc",
    "reliable-push.apps.supb.identity.issuer=https://idp.test.example",
    "reliable-push.apps.supb.identity.audience=supb-mobile",
    "reliable-push.apps.supb.fallback.url=http://localhost:9/hook",
    "reliable-push.apps.supb.fallback.hmac-secret-ref=env:RP_TEST_SHOP_HOOK_SECRET"
})
@Import(SuppressorIT.Fakes.class)
// the context's clock sits in 2102: close it after the class so no worker with that clock outlives these tests
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SuppressorIT extends PostgresTestBase {

    private static final String A = "supa";
    private static final String B = "supb";
    private static final Instant T0 = Instant.parse("2102-01-01T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }

        @Bean
        @Primary
        RecordingWebhook recordingWebhook() {
            return new RecordingWebhook();
        }
    }

    /** Records every call and answers 200. */
    static final class RecordingWebhook implements WebhookClient {

        final Queue<FallbackCall> calls = new ConcurrentLinkedQueue<>();

        @Override
        public WebhookResult send(AppConfig app, FallbackCall call) {
            calls.add(call);
            return new WebhookResult.Success(200);
        }

        long count(List<UUID> ids) {
            return calls.stream().filter(c -> ids.contains(c.trackingId())).count();
        }
    }

    /** Counts the chunk statements of the suppressor. */
    static final class CountingNotifications extends NotificationRepository {

        final List<Integer> chunks = new ArrayList<>();

        CountingNotifications(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public int expireClaimable(String appId, Instant now, int chunk) {
            int n = super.expireClaimable(appId, now, chunk);
            chunks.add(n);
            return n;
        }
    }

    @Autowired
    MutableClock clock;

    @Autowired
    FallbackScheduler scheduler;

    @Autowired
    Suppressor suppressor;

    @Autowired
    CircuitBreaker breaker;

    @Autowired
    CircuitRepository circuits;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    TrackingService tracking;

    @Autowired
    RecordingWebhook webhook;

    @Autowired
    RpMetrics metrics;

    @Autowired
    AppRegistry apps;

    @Autowired
    MeterRegistry meters;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcClient jdbc;

    private EventCapture capture;
    private Instant now;

    @BeforeEach
    void setUp() {
        clock.advance(Duration.ofHours(1));
        now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        capture = new EventCapture();
    }

    @AfterEach
    void tearDown() {
        capture.close();
        for (String table : List.of("notification", "fallback_circuit", "fallback_circuit_event", "device")) {
            jdbc.sql("delete from " + table + " where app_id in ('supa', 'supb')").update();
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** {@code n} sends of ten minutes ago, never delivered, due 30 s ago (the fallbacks about to fire). */
    private List<UUID> due(String app, int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload,
                                                      state, created_at, due_at)
                            values (:id, :app, :user, 'NOT_DELIVERED', 10, '{}'::jsonb, 'PENDING', :created, :due)
                            """)
                    .param("id", id)
                    .param("app", app)
                    .param("user", "u-" + i)
                    .param("created", Timestamp.from(now.minus(Duration.ofMinutes(10)).minusMillis(i)))
                    .param("due", Timestamp.from(now.minusSeconds(30)))
                    .update();
            ids.add(id);
        }
        return ids;
    }

    /** A row claimed by a worker: DISPATCHING, {@code next_attempt_at} and {@code lease_until} as given. */
    private UUID dispatching(String app, Instant nextAttemptAt, Instant leaseUntil) {
        UUID id = due(app, 1).get(0);
        jdbc.sql("update notification set state = 'DISPATCHING', next_attempt_at = :next, lease_until = :lease"
                        + " where id = :id")
                .param("next", Timestamp.from(nextAttemptAt))
                .param("lease", leaseUntil == null ? null : Timestamp.from(leaseUntil))
                .param("id", id)
                .update();
        return id;
    }

    /** {@code n} unmet rows of {@code app} due 30 s ago, in one statement. */
    private void backlog(String app, int n) {
        jdbc.sql("""
                        insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state,
                                                  created_at, due_at)
                        select gen_random_uuid(), :app, 'u-' || g, 'NOT_DELIVERED', 10, '{}'::jsonb, 'PENDING',
                               :created, :due
                          from generate_series(1, :n) g
                        """)
                .param("app", app)
                .param("n", n)
                .param("created", Timestamp.from(now.minus(Duration.ofMinutes(10))))
                .param("due", Timestamp.from(now.minusSeconds(30)))
                .update();
    }

    private void open(String app) {
        circuits.transition(new Circuit(app, CircuitState.OPEN, now, null, null, 0),
                new CircuitEvent(0, app, now, "OPENED", "tracking_missing", 50L, null, null, null));
    }

    private record Row(String state, Instant dueAt, Instant closedAt, Instant nextAttemptAt, Instant leaseUntil) {}

    private Row row(UUID id) {
        return jdbc.sql("select state, due_at, closed_at, next_attempt_at, lease_until from notification where id = :id")
                .param("id", id)
                .query((rs, n) -> new Row(rs.getString("state"), instant(rs.getObject("due_at", OffsetDateTime.class)),
                        instant(rs.getObject("closed_at", OffsetDateTime.class)),
                        instant(rs.getObject("next_attempt_at", OffsetDateTime.class)),
                        instant(rs.getObject("lease_until", OffsetDateTime.class))))
                .single();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private void assertSuppressed(UUID id) {
        assertThat(row(id)).isEqualTo(new Row("FALLBACK_EXPIRED", null, now, null, null));
    }

    private long count(String app, String state) {
        return jdbc.sql("select count(*) from notification where app_id = :app and state = :state")
                .param("app", app)
                .param("state", state)
                .query(Long.class)
                .single();
    }

    private List<LogEvent> suppressedLogs(String app) {
        return capture.events().stream()
                .filter(e -> "fallback.suppressed".equals(EventCapture.data(e).get("event.action")))
                .filter(e -> app.equals(EventCapture.data(e).get("rp.app_id")))
                .toList();
    }

    private double suppressedMetric(String app, String reason) {
        return meters.get("rp.fallback.suppressed").tag("app", app).tag("reason", reason).counter().count();
    }

    private double expiredMetric() {
        return meters.get("rp.fallback").tag("outcome", "expired").counter().count();
    }

    // ---- the tick ----------------------------------------------------------------------------------------------

    @Test
    void openCircuitExpiresBacklogWithoutWebhook() {
        List<UUID> storm = due(A, 60);
        double expiredBefore = expiredMetric();
        double suppressedBefore = suppressedMetric(A, "circuit_open");

        scheduler.tick();

        assertThat(webhook.count(storm)).isZero();
        storm.forEach(this::assertSuppressed);
        assertThat(suppressedLogs(A)).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(EventCapture.data(e))
                    .containsEntry("count", 60L)
                    .containsEntry("reason", "circuit_open")
                    .doesNotContainKey("rp.tracking_id");
        });
        assertThat(circuits.find(A)).hasValueSatisfying(c -> {
            assertThat(c.state()).isEqualTo(CircuitState.OPEN);
            assertThat(c.suppressed()).isEqualTo(60);
        });
        assertThat(suppressedMetric(A, "circuit_open")).isEqualTo(suppressedBefore + 60);
        assertThat(expiredMetric()).isEqualTo(expiredBefore + 60);
        // never one line per notification: no event names a suppressed row
        Set<String> ids = storm.stream().map(UUID::toString).collect(Collectors.toSet());
        assertThat(capture.events()).noneMatch(e -> ids.contains(String.valueOf(EventCapture.data(e).get("rp.tracking_id")))
                || ids.contains(String.valueOf(e.getContextData().<Object>getValue("rp.tracking_id"))));
    }

    @Test
    void otherAppsStillFire() {
        List<UUID> storm = due(A, 60);
        List<UUID> healthy = due(B, 1);

        scheduler.tick();

        assertThat(webhook.count(storm)).isZero();
        assertThat(webhook.count(healthy)).isEqualTo(1);
        assertThat(row(healthy.get(0)).state()).isEqualTo("FALLBACK_TRIGGERED");
        assertThat(count(A, "FALLBACK_EXPIRED")).isEqualTo(60);
        assertThat(suppressedLogs(B)).isEmpty();
    }

    @Test
    void lockTimeoutClaimsNothing() throws Exception {
        List<UUID> healthy = due(B, 1);
        double failedBefore = meters.get("rp.fallback.circuit.check.failed").counter().count();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // another replica holds the detector lock longer than the lock timeout
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(s -> {
            jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", CircuitBreaker.LOCK_KEY).query().listOfRows();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        int claimed;
        try {
            claimed = scheduler.tick();
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(claimed).isZero();
        assertThat(webhook.count(healthy)).isZero();
        assertThat(row(healthy.get(0)).state()).isEqualTo("PENDING");
        assertThat(capture.events()).filteredOn(e -> "fallback.circuit_check_failed"
                        .equals(EventCapture.data(e).get("event.action")))
                .singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
        assertThat(meters.get("rp.fallback.circuit.check.failed").counter().count()).isEqualTo(failedBefore + 1);

        scheduler.tick(); // the lock is free again: the next tick fires it
        assertThat(webhook.count(healthy)).isEqualTo(1);
    }

    @Test
    void claimSkipsExcludedApps() {
        List<UUID> a = due(A, 2);
        List<UUID> b = due(B, 2);

        List<ClaimedNotification> claimed = notifications.claimDue(now, now.plusSeconds(120), 1000, Set.of(A));

        List<UUID> ids = claimed.stream().map(ClaimedNotification::id).toList();
        assertThat(ids).containsAll(b).doesNotContainAnyElementsOf(a);
        assertThat(count(A, "PENDING")).isEqualTo(2);
    }

    // ---- the suppressor ----------------------------------------------------------------------------------------

    @Test
    void suspendedSuppressesEveryAppEvenForced() {
        breaker.force(A, "ops-1");
        List<UUID> forced = due(A, 3);
        List<UUID> closed = due(B, 2);
        Map<String, Circuit> states = breaker.evaluate(List.of(A, B));
        assertThat(states.get(A).state()).isEqualTo(CircuitState.FORCED);
        double before = suppressedMetric(B, "fallback_suspended");

        Set<String> suppressed = suppressor.suppress(states, true, now);

        assertThat(suppressed).containsExactlyInAnyOrder(A, B);
        forced.forEach(this::assertSuppressed);
        closed.forEach(this::assertSuppressed);
        assertThat(suppressedLogs(A)).singleElement().satisfies(e -> assertThat(EventCapture.data(e))
                .containsEntry("count", 3L).containsEntry("reason", "fallback_suspended"));
        assertThat(suppressedLogs(B)).singleElement().satisfies(e -> assertThat(EventCapture.data(e))
                .containsEntry("count", 2L).containsEntry("reason", "fallback_suspended"));
        assertThat(circuits.find(A)).hasValueSatisfying(c -> {
            assertThat(c.state()).isEqualTo(CircuitState.FORCED);
            assertThat(c.suppressed()).isEqualTo(3);
        });
        // B had no row: a CLOSED one keeps the count
        assertThat(circuits.find(B)).hasValue(new Circuit(B, CircuitState.CLOSED, now, null, null, 2));
        assertThat(suppressedMetric(B, "fallback_suspended")).isEqualTo(before + 2);
    }

    @Test
    void closedAndForcedCircuitsAreNotSuppressedWhenNotSuspended() {
        breaker.force(A, "ops-1");
        List<UUID> rows = due(A, 2);
        due(B, 2);

        Set<String> suppressed = suppressor.suppress(breaker.evaluate(List.of(A, B)), false, now);

        assertThat(suppressed).isEmpty();
        assertThat(row(rows.get(0)).state()).isEqualTo("PENDING");
        assertThat(count(B, "PENDING")).isEqualTo(2);
        assertThat(capture.events()).noneMatch(e -> "fallback.suppressed".equals(EventCapture.data(e).get("event.action")));
    }

    @Test
    void openCircuitWithoutClaimableRowsIsExcludedSilently() {
        open(A);

        assertThat(suppressor.suppress(Map.of(A, circuits.find(A).orElseThrow()), false, now)).containsExactly(A);
        assertThat(suppressedLogs(A)).isEmpty();
        assertThat(circuits.find(A).orElseThrow().suppressed()).isZero();
    }

    @Test
    void liveLeaseIsLeftAlone() {
        open(A);
        UUID live = dispatching(A, now.plusSeconds(120), now.plusSeconds(120)); // a worker is calling the webhook
        UUID crashed = dispatching(A, now.minusSeconds(1), now.minusSeconds(1)); // its worker's lease ran out
        UUID retry = dispatching(A, now.minusSeconds(1), null); // a retry whose backoff is over
        // defensive: the lease decides, even if next_attempt_at already passed
        UUID leased = dispatching(A, now.minusSeconds(1), now.plusSeconds(120));
        UUID pending = due(A, 1).get(0);

        suppressor.suppress(Map.of(A, circuits.find(A).orElseThrow()), false, now);

        assertThat(row(live)).isEqualTo(new Row("DISPATCHING", now.minusSeconds(30), null, now.plusSeconds(120),
                now.plusSeconds(120)));
        assertThat(row(leased).state()).isEqualTo("DISPATCHING");
        assertThat(row(leased).leaseUntil()).isEqualTo(now.plusSeconds(120));
        assertSuppressed(crashed);
        assertSuppressed(retry);
        assertSuppressed(pending);
        assertThat(suppressedLogs(A)).singleElement()
                .satisfies(e -> assertThat(EventCapture.data(e)).containsEntry("count", 3L));
    }

    @Test
    void chunksOf1000() {
        open(A);
        backlog(A, 2500);
        CountingNotifications counting = new CountingNotifications(jdbc);
        Suppressor chunked = new Suppressor(counting, circuits, metrics, transactions, apps);

        assertThat(chunked.suppress(Map.of(A, circuits.find(A).orElseThrow()), false, now)).containsExactly(A);

        assertThat(counting.chunks).containsExactly(1000, 1000, 500);
        assertThat(count(A, "FALLBACK_EXPIRED")).isEqualTo(2500);
        assertThat(circuits.find(A).orElseThrow().suppressed()).isEqualTo(2500);
        assertThat(suppressedLogs(A)).singleElement()
                .satisfies(e -> assertThat(EventCapture.data(e)).containsEntry("count", 2500L));
    }

    @Test
    void resumeBetweenChunksStopsTheSuppression() {
        open(A);
        backlog(A, 2500);
        CountingNotifications counting = new CountingNotifications(jdbc);
        // an operator resumes the circuit right after the first chunk committed
        RpMetrics resumeAfterFirstChunk = new RpMetrics(meters) {
            private boolean resumed;

            @Override
            public void fallbackSuppressed(String appId, String reason, long count) {
                super.fallbackSuppressed(appId, reason, count);
                if (!resumed) {
                    resumed = true;
                    breaker.force(A, "ops-1");
                }
            }
        };
        Suppressor chunked = new Suppressor(counting, circuits, resumeAfterFirstChunk, transactions, apps);

        chunked.suppress(Map.of(A, circuits.find(A).orElseThrow()), false, now);

        // the pass stops at the next chunk: the circuit is no longer OPEN
        assertThat(counting.chunks).containsExactly(1000);
        assertThat(count(A, "FALLBACK_EXPIRED")).isEqualTo(1000);
        assertThat(count(A, "PENDING")).isEqualTo(1500);
        assertThat(circuits.find(A).orElseThrow().state()).isEqualTo(CircuitState.FORCED);
        assertThat(suppressedLogs(A)).singleElement()
                .satisfies(e -> assertThat(EventCapture.data(e)).containsEntry("count", 1000L));
    }

    @Test
    void suspendedSuppressionDoesNotDependOnTheCircuit() {
        breaker.force(A, "ops-1");
        backlog(A, 1500);
        CountingNotifications counting = new CountingNotifications(jdbc);
        Suppressor chunked = new Suppressor(counting, circuits, metrics, transactions, apps);

        chunked.suppress(Map.of(A, circuits.find(A).orElseThrow()), true, now);

        assertThat(counting.chunks).containsExactly(1000, 500);
        assertThat(count(A, "FALLBACK_EXPIRED")).isEqualTo(1500);
    }

    @Test
    void lateEventAfterSuppressionIsLate() {
        open(A);
        UUID id = due(A, 1).get(0);
        suppressor.suppress(Map.of(A, circuits.find(A).orElseThrow()), false, now);
        assertSuppressed(id);

        UUID deviceId = UUID.randomUUID();
        jdbc.sql("""
                insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at)
                values (?, 'supa', 'u-0', 'ANDROID', 0, now(), now())
                """).param(deviceId).update();
        EventBatchResult result = tracking.process(new DevicePrincipal(A, "u-0", deviceId, 0),
                List.of(new DeviceEvent(id, EventType.DELIVERED, clock.instant())));

        assertThat(result.items().get(0).outcome()).isEqualTo(EventBatchResult.Outcome.LATE);
        assertThat(row(id).state()).isEqualTo("FALLBACK_EXPIRED");
        assertThat(jdbc.sql("select late from notification_event where notification_id = ?").param(id)
                .query(Boolean.class).single()).isTrue();
    }
}
