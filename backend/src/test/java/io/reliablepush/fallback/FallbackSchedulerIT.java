package io.reliablepush.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.reliablepush.apps.AppConfig;
import io.reliablepush.notifications.FallbackPolicy;
import io.reliablepush.notifications.Notification;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.notifications.NotificationState;
import io.reliablepush.shared.logging.EventCapture;
import io.reliablepush.shared.security.DevicePrincipal;
import io.reliablepush.shared.time.TimeScale;
import io.reliablepush.support.MutableClock;
import io.reliablepush.support.PostgresTestBase;
import io.reliablepush.tracking.DeviceEvent;
import io.reliablepush.tracking.EventBatchResult;
import io.reliablepush.tracking.EventType;
import io.reliablepush.tracking.TrackingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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

/**
 * The fallback worker against a real PostgreSQL (spec 2): claiming, lease, trigger window, retries. The fast clock is
 * on ({@code e2e}, 1 product minute = 1 s), time comes from a {@link MutableClock} and the webhook is a recording fake.
 * Ticks are driven by the tests: the scheduled poll is pushed out to one day.
 *
 * <p>The database is shared with every other IT and claiming is global, so a tick also claims leftover rows of other
 * tests. The clock runs in the year 2100: rows created here are never due for workers of other cached contexts (real
 * clock), and every assertion is scoped to this test's tracking ids. The exactly-once tests use dedicated apps.
 */
@ActiveProfiles({"test", "worker", "e2e"})
@TestPropertySource(properties = {
    "reliable-push.testing.minute-duration=1s",
    // the test calls tick() itself; the scheduled poll (first run after one interval) never fires during the class
    "reliable-push.worker.poll-interval=1d",
    "reliable-push.apps.fbit.sender-api-key-sha256=a2cd39316c98ea096773f14854fd45f790f78b9df744dc4be3555cd2f662ba99",
    "reliable-push.apps.fbit.identity.type=oidc",
    "reliable-push.apps.fbit.identity.issuer=https://idp.test.example",
    "reliable-push.apps.fbit.identity.audience=fbit-mobile",
    "reliable-push.apps.fbit.fallback.url=http://localhost:9/hook",
    "reliable-push.apps.fbit.fallback.hmac-secret-ref=env:RP_TEST_SHOP_HOOK_SECRET",
    "reliable-push.apps.fbrace.sender-api-key-sha256=2c7476e28eda010a8fd23167d35628f34384ef72f4c2c568c27bb39a5d016c00",
    "reliable-push.apps.fbrace.identity.type=oidc",
    "reliable-push.apps.fbrace.identity.issuer=https://idp.test.example",
    "reliable-push.apps.fbrace.identity.audience=fbrace-mobile",
    "reliable-push.apps.fbrace.fallback.url=http://localhost:9/hook",
    "reliable-push.apps.fbrace.fallback.hmac-secret-ref=env:RP_TEST_SHOP_HOOK_SECRET"
})
@Import(FallbackSchedulerIT.Fakes.class)
// the context's clock sits in 2100: close it after the class so no worker with that clock outlives these tests
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FallbackSchedulerIT extends PostgresTestBase {

    private static final String APP = "fbit";
    private static final String RACE_APP = "fbrace";
    private static final Instant T0 = Instant.parse("2100-01-01T00:00:00Z");
    private static final String PAYLOAD_MARKER = "fallback-secret-content";

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }

        @Bean
        @Primary
        FakeWebhookClient fakeWebhookClient() {
            return new FakeWebhookClient();
        }
    }

    /** Records every call; answers with a per-tracking-id script (default: success). */
    static final class FakeWebhookClient implements WebhookClient {

        final Queue<FallbackCall> calls = new ConcurrentLinkedQueue<>();
        final Map<UUID, Function<FallbackCall, WebhookResult>> script = new ConcurrentHashMap<>();

        @Override
        public WebhookResult send(AppConfig app, FallbackCall call) {
            calls.add(call);
            Function<FallbackCall, WebhookResult> answer = script.get(call.trackingId());
            return answer != null ? answer.apply(call) : new WebhookResult.Success(200);
        }

        long count(UUID trackingId) {
            return calls.stream().filter(c -> c.trackingId().equals(trackingId)).count();
        }

        List<FallbackCall> callsFor(UUID trackingId) {
            return calls.stream().filter(c -> c.trackingId().equals(trackingId)).toList();
        }
    }

    @Autowired
    FallbackScheduler scheduler;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    TrackingService tracking;

    @Autowired
    FakeWebhookClient webhook;

    @Autowired
    MutableClock clock;

    @Autowired
    Clock appClock;

    @Autowired
    TimeScale timeScale;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    private EventCapture capture;

    @BeforeEach
    void freshTimeAndCapture() {
        // every test starts one (real) hour later, so leftovers of earlier tests are long due and never in the way
        clock.advance(Duration.ofHours(1));
        webhook.script.clear();
        capture = new EventCapture();
    }

    @AfterEach
    void stopCapture() {
        capture.close();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Duration minutes(int n) {
        return timeScale.minutes(n);
    }

    private UUID insert(String appId, String userId, FallbackPolicy policy, Instant dueAt) {
        UUID id = UUID.randomUUID();
        String payload = "{\"sms\":\"" + PAYLOAD_MARKER + "\",\"n\":1}";
        notifications.insert(new Notification(id, appId, userId, null, policy, 10, payload,
                NotificationState.PENDING, dueAt.minus(minutes(10)), dueAt, null, null, null));
        return id;
    }

    private UUID insert(FallbackPolicy policy, Instant dueAt) {
        return insert(APP, "user-" + UUID.randomUUID(), policy, dueAt);
    }

    /** A row a crashed worker left behind: DISPATCHING, still holding its lease (lease_until set), with attempts. */
    private UUID dispatchingLeftover(Instant dueAt, Instant leaseUntil, int attempts) {
        UUID id = insert(FallbackPolicy.NOT_DELIVERED, dueAt);
        OffsetDateTime lease = leaseUntil.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        update notification set state = 'DISPATCHING', next_attempt_at = ?, lease_until = ?, fallback_attempts = ?
                         where id = ?
                        """)
                .params(lease, lease, attempts, id)
                .update();
        return id;
    }

    private DevicePrincipal device(String appId, String userId) {
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("""
                insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at)
                values (?, ?, ?, 'ANDROID', 0, now(), now())
                """).params(deviceId, appId, userId).update();
        return new DevicePrincipal(appId, userId, deviceId, 0);
    }

    private EventBatchResult.Outcome report(DevicePrincipal device, UUID trackingId, EventType type) {
        return tracking.process(device, List.of(new DeviceEvent(trackingId, type, appClock.instant())))
                .items().get(0).outcome();
    }

    private Row row(UUID id) {
        return jdbc.sql("""
                        select state, due_at, closed_at, next_attempt_at, fallback_attempts, delivered_at, lease_until
                          from notification where id = ?
                        """)
                .param(id)
                .query((rs, n) -> new Row(
                        NotificationState.valueOf(rs.getString("state")),
                        instant(rs.getObject("due_at", OffsetDateTime.class)),
                        instant(rs.getObject("closed_at", OffsetDateTime.class)),
                        instant(rs.getObject("next_attempt_at", OffsetDateTime.class)),
                        rs.getInt("fallback_attempts"),
                        instant(rs.getObject("delivered_at", OffsetDateTime.class)),
                        instant(rs.getObject("lease_until", OffsetDateTime.class))))
                .single();
    }

    private record Row(
            NotificationState state,
            Instant dueAt,
            Instant closedAt,
            Instant nextAttemptAt,
            int attempts,
            Instant deliveredAt,
            Instant leaseUntil) {}

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** Catalogue events of one tracking id (the worker puts it in the ThreadContext, not in the message). */
    private List<LogEvent> events(UUID trackingId) {
        return List.copyOf(capture.events()).stream()
                .filter(e -> trackingId.toString().equals(e.getContextData().getValue("rp.tracking_id")))
                .toList();
    }

    private List<String> actions(UUID trackingId) {
        return events(trackingId).stream().map(e -> (String) EventCapture.data(e).get("event.action")).toList();
    }

    private LogEvent event(UUID trackingId, String action) {
        return events(trackingId).stream()
                .filter(e -> action.equals(EventCapture.data(e).get("event.action")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + action + " for " + trackingId + ": " + actions(trackingId)));
    }

    private double fallbackCount(String outcome) {
        var counter = meters.find("rp.fallback").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private void assertClosed(UUID id, NotificationState state) {
        Row row = row(id);
        assertThat(row.state()).isEqualTo(state);
        assertThat(row.closedAt()).isNotNull();
        assertThat(row.dueAt()).isNull();
        assertThat(row.nextAttemptAt()).isNull();
        assertThat(row.leaseUntil()).isNull();
    }

    // ---- tests -------------------------------------------------------------------------------------------------

    @Test
    void notDeliveredFiresExactlyOnce() {
        Instant due = now().plus(minutes(10));
        UUID id = insert(FallbackPolicy.NOT_DELIVERED, due);
        double triggeredBefore = fallbackCount("triggered");

        scheduler.tick();
        assertThat(webhook.count(id)).isZero();

        clock.set(due.plusMillis(1));
        scheduler.tick();
        clock.advance(minutes(5));
        scheduler.tick();

        assertThat(webhook.count(id)).isEqualTo(1);
        FallbackCall call = webhook.callsFor(id).get(0);
        assertThat(call.attempt()).isEqualTo(1);
        assertThat(call.reason()).isEqualTo("NOT_DELIVERED");
        assertThat(call.fallback().get("sms").asString()).isEqualTo(PAYLOAD_MARKER);
        assertThat(call.toString()).doesNotContain(PAYLOAD_MARKER).doesNotContain(call.userId());

        assertClosed(id, NotificationState.FALLBACK_TRIGGERED);
        assertThat(row(id).attempts()).isEqualTo(1);
        assertThat(fallbackCount("triggered")).isEqualTo(triggeredBefore + 1);

        assertThat(actions(id)).containsExactly("fallback.claimed", "fallback.dispatched");
        LogEvent dispatched = event(id, "fallback.dispatched");
        assertThat(dispatched.getContextData().<String>getValue("trace.id")).isNotBlank();
        assertThat(event(id, "fallback.claimed").getContextData().<String>getValue("trace.id"))
                .isEqualTo(dispatched.getContextData().<String>getValue("trace.id"));
        assertThat(EventCapture.data(dispatched)).containsEntry("rp.app_id", APP).containsEntry("event.outcome", "success");
        assertThat(List.copyOf(capture.events()))
                .allSatisfy(e -> assertThat(e.getMessage().getFormattedMessage() + EventCapture.data(e))
                        .doesNotContain(PAYLOAD_MARKER));
    }

    @Test
    void deliveredBeforeDueNeverFires() {
        String user = "user-" + UUID.randomUUID();
        DevicePrincipal device = device(APP, user);
        Instant due = now().plus(minutes(10));
        UUID id = insert(APP, user, FallbackPolicy.NOT_DELIVERED, due);

        assertThat(report(device, id, EventType.DELIVERED)).isEqualTo(EventBatchResult.Outcome.ACCEPTED);
        clock.set(due.plus(minutes(1)));
        scheduler.tick();
        clock.advance(minutes(30));
        scheduler.tick();

        assertThat(webhook.count(id)).isZero();
        assertThat(row(id).state()).isEqualTo(NotificationState.DELIVERED);
        assertThat(actions(id)).isEmpty();
    }

    @Test
    void notReadPolicyFiresWhenOnlyDelivered() {
        String user = "user-" + UUID.randomUUID();
        DevicePrincipal device = device(APP, user);
        Instant due = now().plus(minutes(10));
        UUID id = insert(APP, user, FallbackPolicy.NOT_READ, due);

        assertThat(report(device, id, EventType.DELIVERED)).isEqualTo(EventBatchResult.Outcome.ACCEPTED);
        clock.set(due.plusMillis(1));
        scheduler.tick();
        scheduler.tick();

        assertThat(webhook.count(id)).isEqualTo(1);
        assertThat(webhook.callsFor(id).get(0).reason()).isEqualTo("NOT_READ");
        assertClosed(id, NotificationState.FALLBACK_TRIGGERED);
        assertThat(row(id).deliveredAt()).isNotNull();
    }

    @Test
    void twoConcurrentWorkersNeverDoubleFire() throws Exception {
        Instant due = now().minusSeconds(1);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(insert(RACE_APP, "user-" + i, FallbackPolicy.NOT_DELIVERED, due));
        }

        CountDownLatch start = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int w = 0; w < 2; w++) {
                results.add(workers.submit(() -> {
                    start.await();
                    int claimed = 0;
                    // until no row of the app is open; bounded so a broken scheduler fails instead of hanging
                    while (openRows(RACE_APP) > 0 && System.nanoTime() < deadline) {
                        claimed += scheduler.tick();
                    }
                    return claimed;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> result : results) {
                total += result.get(90, TimeUnit.SECONDS);
            }
            assertThat(total).isGreaterThanOrEqualTo(200);
        }
        assertThat(openRows(RACE_APP)).isZero();

        Map<UUID, Long> perId = webhook.calls.stream()
                .map(FallbackCall::trackingId)
                .filter(ids::contains)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(perId).hasSize(200);
        assertThat(perId.values()).containsOnly(1L);
        assertThat(ids).allSatisfy(id -> assertThat(row(id).state()).isEqualTo(NotificationState.FALLBACK_TRIGGERED));
        assertThat(ids).allSatisfy(id -> assertThat(actions(id)).containsExactly("fallback.claimed", "fallback.dispatched"));
    }

    private int openRows(String appId) {
        return jdbc.sql("select count(*) from notification where app_id = ? and state in ('PENDING', 'DELIVERED', 'DISPATCHING')")
                .param(appId)
                .query(Integer.class)
                .single();
    }

    @Test
    void crashedWorkerRowReclaimedAfterLeaseInsideWindow() {
        Instant now = now();
        UUID id = dispatchingLeftover(now.minus(minutes(5)), now.minusMillis(1), 0);
        UUID leased = dispatchingLeftover(now.minus(minutes(5)), now.plus(minutes(1)), 0);

        scheduler.tick();
        scheduler.tick();

        assertThat(webhook.count(id)).isEqualTo(1);
        assertClosed(id, NotificationState.FALLBACK_TRIGGERED);
        assertThat(actions(id)).containsExactly("fallback.reclaimed", "fallback.dispatched");
        assertThat(event(id, "fallback.reclaimed").getLevel()).isEqualTo(Level.WARN);
        // a lease that has not expired yet belongs to a live worker: not touched
        assertThat(webhook.count(leased)).isZero();
        assertThat(row(leased).state()).isEqualTo(NotificationState.DISPATCHING);
    }

    @Test
    void crashedWorkerRowOutsideWindowExpires() {
        Instant now = now();
        // window is 20 product minutes: due 21 minutes ago, lease ran out a moment ago
        UUID id = dispatchingLeftover(now.minus(minutes(21)), now.minusMillis(1), 1);
        double expiredBefore = fallbackCount("expired");

        scheduler.tick();

        assertThat(webhook.count(id)).isZero();
        assertClosed(id, NotificationState.FALLBACK_EXPIRED);
        assertThat(actions(id)).containsExactly("fallback.reclaimed", "fallback.expired");
        assertThat(event(id, "fallback.expired").getLevel()).isEqualTo(Level.WARN);
        assertThat(fallbackCount("expired")).isEqualTo(expiredBefore + 1);
    }

    @Test
    void outageLongerThanWindowExpires() {
        UUID id = insert(FallbackPolicy.NOT_DELIVERED, now().minus(minutes(30)));

        scheduler.tick();

        assertThat(webhook.count(id)).isZero();
        assertClosed(id, NotificationState.FALLBACK_EXPIRED);
        assertThat(actions(id)).containsExactly("fallback.claimed", "fallback.expired");
    }

    @Test
    void deliveredDuringDispatchStaysTriggered() {
        String user = "user-" + UUID.randomUUID();
        DevicePrincipal device = device(APP, user);
        UUID id = insert(APP, user, FallbackPolicy.NOT_DELIVERED, now().minusMillis(1));
        List<EventBatchResult.Outcome> during = new ArrayList<>();
        // the event arrives while the webhook call is in flight: the claim must already be committed (no row lock held)
        webhook.script.put(id, call -> {
            during.add(report(device, id, EventType.DELIVERED));
            return new WebhookResult.Success(202);
        });

        scheduler.tick();

        assertThat(during).containsExactly(EventBatchResult.Outcome.LATE);
        assertThat(webhook.count(id)).isEqualTo(1);
        assertClosed(id, NotificationState.FALLBACK_TRIGGERED);
        assertThat(row(id).deliveredAt()).isNull();
        assertThat(jdbc.sql("select late from notification_event where notification_id = ?").param(id)
                        .query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void retryableFailureIsRetriedAfterBackoffThenTriggers() {
        Instant due = now().minusMillis(1);
        UUID id = insert(FallbackPolicy.NOT_DELIVERED, due);
        webhook.script.put(id, call -> call.attempt() == 1 ? new WebhookResult.Retryable("http_503") : new WebhookResult.Success(200));

        scheduler.tick();

        Row afterFirst = row(id);
        assertThat(afterFirst.state()).isEqualTo(NotificationState.DISPATCHING);
        assertThat(afterFirst.leaseUntil()).isNull();
        assertThat(afterFirst.attempts()).isEqualTo(1);
        assertThat(afterFirst.nextAttemptAt()).isEqualTo(now().plus(minutes(1)));
        assertThat(afterFirst.dueAt()).isEqualTo(due.truncatedTo(ChronoUnit.MICROS));

        scheduler.tick(); // backoff not over yet
        assertThat(webhook.count(id)).isEqualTo(1);

        clock.advance(minutes(1));
        scheduler.tick();

        assertThat(webhook.callsFor(id)).extracting(FallbackCall::attempt).containsExactly(1, 2);
        assertClosed(id, NotificationState.FALLBACK_TRIGGERED);
        assertThat(row(id).attempts()).isEqualTo(2);
        // a due retry is a normal claim: the previous outcome released the lease (only a lost lease is a re-claim)
        assertThat(actions(id)).containsExactly(
                "fallback.claimed", "fallback.retry_scheduled", "fallback.claimed", "fallback.dispatched");
        assertThat(event(id, "fallback.claimed").getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void finalResultClosesAsFailed() {
        UUID id = insert(FallbackPolicy.NOT_DELIVERED, now().minusMillis(1));
        webhook.script.put(id, call -> new WebhookResult.Final("http_400"));
        double failedBefore = fallbackCount("failed");

        scheduler.tick();
        clock.advance(minutes(5));
        scheduler.tick();

        assertThat(webhook.count(id)).isEqualTo(1);
        assertClosed(id, NotificationState.FALLBACK_FAILED);
        assertThat(actions(id)).containsExactly("fallback.claimed", "fallback.failed");
        assertThat(event(id, "fallback.failed").getLevel()).isEqualTo(Level.WARN);
        assertThat(EventCapture.data(event(id, "fallback.failed"))).containsEntry("reason", "http_400");
        assertThat(fallbackCount("failed")).isEqualTo(failedBefore + 1);
    }

    @Test
    void retriesThatWouldLeaveTheWindowExpire() {
        // due 18 product minutes ago: attempt fails, next backoff (1 min) ends at due+19m: still inside;
        // the second failure would need 2 more minutes -> due+21m > window of 20 -> expired
        Instant due = now().minus(minutes(18));
        UUID id = insert(FallbackPolicy.NOT_DELIVERED, due);
        webhook.script.put(id, call -> new WebhookResult.Retryable("timeout"));

        scheduler.tick();
        assertThat(row(id).state()).isEqualTo(NotificationState.DISPATCHING);
        clock.advance(minutes(1));
        scheduler.tick();

        assertThat(webhook.count(id)).isEqualTo(2);
        assertClosed(id, NotificationState.FALLBACK_EXPIRED);
        assertThat(actions(id)).containsExactly(
                "fallback.claimed", "fallback.retry_scheduled", "fallback.claimed", "fallback.expired");
    }

    @Test
    void outcomeOfAWorkerWhoseLeaseRanOutIsDropped() {
        UUID id = insert(FallbackPolicy.NOT_DELIVERED, now().minusMillis(1));
        // first call hangs past the lease; meanwhile a second worker re-claims the row and delivers it
        webhook.script.put(id, call -> {
            webhook.script.remove(id);
            clock.advance(minutes(3));
            scheduler.tick();
            return new WebhookResult.Retryable("timeout");
        });

        List<LogEvent> dropped;
        try (EventCapture dispatcherLog = new EventCapture(FallbackDispatcher.class.getName())) {
            scheduler.tick();
            dropped = List.copyOf(dispatcherLog.events()).stream()
                    .filter(e -> id.toString().equals(e.getContextData().getValue("rp.tracking_id")))
                    .toList();
        }

        // at-least-once under lease expiry: two calls (receivers dedupe on Idempotency-Key), one persisted outcome
        assertThat(webhook.count(id)).isEqualTo(2);
        assertClosed(id, NotificationState.FALLBACK_TRIGGERED);
        assertThat(row(id).attempts()).isEqualTo(1);
        assertThat(actions(id)).containsExactly("fallback.claimed", "fallback.reclaimed", "fallback.dispatched");
        // the late worker's retry is not persisted over the closed row: logged and dropped
        assertThat(dropped).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getMessage().getFormattedMessage()).contains("dropped");
        });
    }

    @Test
    void staleLeaseHolderFinishingFirstIsDroppedAndReclaimerWins() throws Exception {
        UUID id = insert(FallbackPolicy.NOT_DELIVERED, now().minusMillis(1));
        CountDownLatch firstInCall = new CountDownLatch(1);
        CountDownLatch secondInCall = new CountDownLatch(1);
        CountDownLatch firstPersisted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        webhook.script.put(id, call -> {
            try {
                if (calls.incrementAndGet() == 1) {
                    // worker A: slow call, its lease runs out meanwhile; it fails after B re-claimed and called
                    firstInCall.countDown();
                    assertThat(secondInCall.await(30, TimeUnit.SECONDS)).isTrue();
                    return new WebhookResult.Retryable("timeout");
                }
                // worker B: the re-claimer; succeeds only after A tried to persist its stale outcome
                secondInCall.countDown();
                assertThat(firstPersisted.await(30, TimeUnit.SECONDS)).isTrue();
                return new WebhookResult.Success(200);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });

        List<LogEvent> dropped;
        try (EventCapture dispatcherLog = new EventCapture(FallbackDispatcher.class.getName());
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Integer> workerA = workers.submit(scheduler::tick);
            assertThat(firstInCall.await(30, TimeUnit.SECONDS)).isTrue();
            clock.advance(minutes(3)); // past A's lease (2 product minutes)
            Future<Integer> workerB = workers.submit(scheduler::tick);

            workerA.get(30, TimeUnit.SECONDS);
            Row afterStale = row(id);
            firstPersisted.countDown();
            workerB.get(30, TimeUnit.SECONDS);

            // A's Retryable was fenced off by B's newer lease: nothing written
            assertThat(afterStale.state()).isEqualTo(NotificationState.DISPATCHING);
            assertThat(afterStale.attempts()).isZero();
            dropped = List.copyOf(dispatcherLog.events()).stream()
                    .filter(e -> id.toString().equals(e.getContextData().getValue("rp.tracking_id")))
                    .toList();
        }

        assertThat(webhook.count(id)).isEqualTo(2);
        assertClosed(id, NotificationState.FALLBACK_TRIGGERED);
        assertThat(row(id).attempts()).isEqualTo(1);
        assertThat(actions(id)).containsExactly("fallback.claimed", "fallback.reclaimed", "fallback.dispatched");
        assertThat(dropped).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void rowOfAnUnconfiguredAppIsLeftForLater() {
        UUID id = insert("fb-unknown-app", "user-" + UUID.randomUUID(), FallbackPolicy.NOT_DELIVERED, now().minusMillis(1));

        scheduler.tick();

        assertThat(webhook.count(id)).isZero();
        Row row = row(id);
        assertThat(row.state()).isEqualTo(NotificationState.DISPATCHING);
        assertThat(row.attempts()).isZero();
        assertThat(row.nextAttemptAt()).isAfter(now());
        assertThat(row.leaseUntil()).isNull();
        assertThat(actions(id)).containsExactly("fallback.claimed", "fallback.retry_scheduled");
        assertThat(EventCapture.data(event(id, "fallback.retry_scheduled"))).containsEntry("reason", "app_not_configured");

        // do not leave a forever-due row in the shared database
        jdbc.sql("delete from notification where id = ?").param(id).update();
    }

    @Test
    void rowOfAnUnconfiguredAppExpiresAfterTheDefaultWindow() {
        // no app config, so the default trigger window (20 product minutes) bounds the grace period
        UUID id = insert("fb-unknown-app", "user-" + UUID.randomUUID(), FallbackPolicy.NOT_DELIVERED, now().minus(minutes(21)));
        double expiredBefore = fallbackCount("expired");

        scheduler.tick();

        assertThat(webhook.count(id)).isZero();
        assertClosed(id, NotificationState.FALLBACK_EXPIRED);
        assertThat(actions(id)).containsExactly("fallback.claimed", "fallback.expired");
        assertThat(EventCapture.data(event(id, "fallback.expired"))).containsEntry("reason", "app_not_configured");
        assertThat(fallbackCount("expired")).isEqualTo(expiredBefore + 1);
    }
}
