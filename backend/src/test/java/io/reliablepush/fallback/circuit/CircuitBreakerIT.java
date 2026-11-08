package io.reliablepush.fallback.circuit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.shared.logging.EventCapture;
import io.reliablepush.shared.metrics.RpMetrics;
import io.reliablepush.support.MutableClock;
import io.reliablepush.support.PostgresTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Detection and transitions of the fallback circuit (spec 4) against a real PostgreSQL. The detector reads the
 * application clock, here a {@link MutableClock} in the year 2101 (as {@code FallbackSchedulerIT} does): the rows
 * written here are never due for the workers of other cached contexts (real clock), and tests move time forward
 * without sleeping. Each test uses its own app id and every row is deleted after the test.
 */
@ActiveProfiles({"api", "worker", "test"})
@TestPropertySource(properties = {
    "reliable-push.worker.poll-interval=1d",
    "reliable-push.fallback.circuit-breaker.enabled=true" // off in the test profile
})
@Import(CircuitBreakerIT.Fakes.class)
// the context's clock sits in 2101: close it after the class so no worker with that clock outlives these tests
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CircuitBreakerIT extends PostgresTestBase {

    private static final String PREFIX = "cbit-";
    private static final Instant T0 = Instant.parse("2101-01-01T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    MutableClock clock;

    @Autowired
    CircuitBreaker breaker;

    @Autowired
    CircuitRepository circuits;

    @Autowired
    DetectionQueries queries;

    @Autowired
    CircuitBreakerProperties properties;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    RpMetrics metrics;

    @Autowired
    AppRegistry apps;

    private EventCapture capture;
    private String app;
    private Instant now;

    @BeforeEach
    void setUp() {
        // every test starts one hour later: the windows of earlier tests are long past
        clock.advance(Duration.ofHours(1));
        app = PREFIX + UUID.randomUUID().toString().substring(0, 8);
        now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        capture = new EventCapture();
    }

    @AfterEach
    void tearDown() {
        capture.close();
        jdbc.sql("delete from notification where app_id like 'cbit-%'").update();
        jdbc.sql("delete from fallback_circuit where app_id like 'cbit-%'").update();
        jdbc.sql("delete from fallback_circuit_event where app_id like 'cbit-%'").update();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /**
     * Inserts {@code n} rows created {@code createdAgo} before now (one millisecond apart, newest first). A row is
     * due {@code dueAgo} before now when {@code dueAgo} is set (open rows), delivered when {@code delivered}.
     */
    private void insert(String appId, int n, String policy, String state, Duration createdAgo, Duration dueAgo,
            boolean delivered, boolean closed) {
        for (int i = 0; i < n; i++) {
            Instant created = now.minus(createdAgo).minusMillis(i);
            jdbc.sql("""
                            insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload,
                                                      state, created_at, due_at, delivered_at, closed_at)
                            values (:id, :app, :user, :policy, 10, '{}'::jsonb, :state, :created, :due, :delivered,
                                    :closed)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("app", appId)
                    .param("user", "u-" + i)
                    .param("policy", policy)
                    .param("state", state)
                    .param("created", Timestamp.from(created))
                    .param("due", dueAgo == null ? null : Timestamp.from(now.minus(dueAgo)))
                    .param("delivered", delivered ? Timestamp.from(created.plusSeconds(1)) : null)
                    .param("closed", closed ? Timestamp.from(created.plusSeconds(1)) : null)
                    .update();
        }
    }

    /** {@code n} sends that are due now and were never delivered (the fallbacks about to fire). */
    private void unmetDue(String appId, int n, Duration createdAgo) {
        insert(appId, n, "NOT_DELIVERED", "PENDING", createdAgo, Duration.ofSeconds(30), false, false);
    }

    /** {@code n} sends delivered under NOT_DELIVERED: closed, not due. */
    private void delivered(String appId, int n, Duration createdAgo) {
        insert(appId, n, "NOT_DELIVERED", "DELIVERED", createdAgo, null, true, true);
    }

    /**
     * {@code total} sends ten minutes ago, interleaved: 7 of every 10 delivered, the others due now and unmet. Under
     * NOT_DELIVERED a delivered row is closed; under NOT_READ it stays due (DELIVERED, not read).
     */
    private void campaign(String policy, int total) {
        boolean notRead = "NOT_READ".equals(policy);
        for (int i = 0; i < total; i++) {
            Duration ago = Duration.ofMinutes(10).plusMillis(i);
            if (i % 10 < 7) {
                insert(app, 1, policy, "DELIVERED", ago, notRead ? Duration.ofSeconds(30) : null, true, !notRead);
            } else {
                insert(app, 1, policy, "PENDING", ago, Duration.ofSeconds(30), false, false);
            }
        }
    }

    private List<CircuitEvent> events(String appId) {
        return circuits.events(appId, 100, null);
    }

    /** {@code rp_fallback_circuit_transitions_total{app,transition}} of this test's app (0 when never counted). */
    private double transitions(String transition) {
        var counter = meters.find("rp.fallback.circuit.transitions").tag("app", app).tag("transition", transition)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private List<LogEvent> logged(String action) {
        return capture.events().stream()
                .filter(e -> action.equals(EventCapture.data(e).get("event.action")))
                .toList();
    }

    private CircuitBreaker breaker(CircuitBreakerProperties p) {
        return new CircuitBreaker(circuits, queries, p, transactions, jdbc, clock, metrics, apps, 400);
    }

    private static CircuitBreakerProperties disabled() {
        CircuitBreakerProperties d = CircuitBreakerProperties.defaults();
        return new CircuitBreakerProperties(d.suspended(), false, d.window(), d.minDue(), d.maxDeliveredRatio(),
                d.closeMinDelivered(), d.minOpen(), d.forceHold());
    }

    // ---- opening -----------------------------------------------------------------------------------------------

    @Test
    void stormOpensBeforeAnyClaim() {
        unmetDue(app, 60, Duration.ofMinutes(10));

        Map<String, Circuit> result = breaker.evaluate(List.of(app));

        assertThat(result.get(app).state()).isEqualTo(CircuitState.OPEN);
        assertThat(circuits.find(app)).hasValueSatisfying(c -> assertThat(c.state()).isEqualTo(CircuitState.OPEN));
        assertThat(events(app)).singleElement().satisfies(e -> {
            assertThat(e.transition()).isEqualTo("OPENED");
            assertThat(e.reason()).isEqualTo("tracking_missing");
            assertThat(e.due()).isEqualTo(50L); // counted up to min-due only
            assertThat(e.ratio()).isEqualByComparingTo("0");
        });
        // detection never touches the notifications: the backlog is still open, waiting for the suppressor
        assertThat(jdbc.sql("select count(*) from notification where app_id = :app and closed_at is null")
                        .param("app", app).query(Long.class).single()).isEqualTo(60);

        assertThat(logged("fallback.circuit_opened")).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(EventCapture.data(e))
                    .containsEntry("rp.app_id", app)
                    .containsEntry("due", 50L)
                    .containsEntry("ratio", 0.0);
        });

        assertThat(transitions("OPENED")).isEqualTo(1);

        // a second evaluation keeps it open without a new event
        breaker.evaluate(List.of(app));
        assertThat(events(app)).hasSize(1);
        assertThat(transitions("OPENED")).isEqualTo(1);
    }

    @Test
    void openedLogsTheRawRatioAndStoresFourDecimals() {
        // exactly min-due due rows (all read) and 3 delivered sends created inside their creation range: 3 / 53
        unmetDue(app, 50, Duration.ofMinutes(10));
        delivered(app, 3, Duration.ofMinutes(10).plusMillis(20));

        assertThat(breaker.evaluate(List.of(app)).get(app).state()).isEqualTo(CircuitState.OPEN);

        assertThat(events(app)).singleElement()
                .satisfies(e -> assertThat(e.ratio()).isEqualByComparingTo("0.0566"));
        assertThat(logged("fallback.circuit_opened")).singleElement()
                .satisfies(e -> assertThat(EventCapture.data(e)).containsEntry("ratio", 3.0 / 53));
    }

    @Test
    void campaignThenSilenceStaysClosed() {
        // 200 sends ten minutes ago, 70 % delivered; the 60 offline users' fallbacks come due now, no new traffic
        campaign("NOT_DELIVERED", 200);

        assertThat(queries.due(app, now, properties.window(), 50)).isEqualTo(50);
        assertThat(queries.cohortRatio(app, now, properties.window(), 50)).isGreaterThan(0.5);
        assertThat(breaker.evaluate(List.of(app)).get(app).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(events(app)).isEmpty();
    }

    @Test
    void usersWithoutDeviceAreNotPartOfTheCohort() {
        // the campaign of campaignThenSilenceStaysClosed, plus ten untracked sends (no device) per tracked one
        campaign("NOT_DELIVERED", 200);
        jdbc.sql("""
                        insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state,
                                                  created_at, closed_at)
                        select gen_random_uuid(), :app, 'u-none', 'NOT_DELIVERED', 10, '{}'::jsonb,
                               'NO_DEVICE_REGISTERED', c.at, c.at
                          from (select cast(:base as timestamptz) - (g % 200) * interval '1 millisecond' as at
                                  from generate_series(0, 1999) g) c
                        """)
                .param("app", app)
                .param("base", Timestamp.from(now.minus(Duration.ofMinutes(10))))
                .update();

        assertThat(queries.cohortRatio(app, now, properties.window(), 50)).isGreaterThan(0.5);
        assertThat(breaker.evaluate(List.of(app)).get(app).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(events(app)).isEmpty();
    }

    @Test
    void notReadDeliveredRowsCountAsDelivered() {
        // NOT_READ: delivered but unopened rows come due (DELIVERED, due_at set); their delivery counts in the cohort
        campaign("NOT_READ", 100);

        assertThat(queries.due(app, now, properties.window(), 50)).isEqualTo(50);
        assertThat(breaker.evaluate(List.of(app)).get(app).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(events(app)).isEmpty();
    }

    @Test
    void outageDuringSendsTrips() {
        // sends ten minutes ago were never confirmed; tracking works again for the new sends of the last minute
        unmetDue(app, 60, Duration.ofMinutes(10));
        delivered(app, 100, Duration.ofMinutes(1));

        assertThat(breaker.evaluate(List.of(app)).get(app).state()).isEqualTo(CircuitState.OPEN);
        assertThat(events(app)).extracting(CircuitEvent::transition).containsExactly("OPENED");
    }

    @Test
    void closedFallbacksOfTheWindowAreDue() {
        // the window's fallbacks already fired (FALLBACK_TRIGGERED / FALLBACK_FAILED, closed_at in the window)
        insert(app, 30, "NOT_DELIVERED", "FALLBACK_TRIGGERED", Duration.ofMinutes(10), null, false, false);
        insert(app, 30, "NOT_DELIVERED", "FALLBACK_FAILED", Duration.ofMinutes(10).plusMillis(30), null, false,
                false);
        jdbc.sql("update notification set closed_at = :at where app_id = :app")
                .param("at", Timestamp.from(now.minusSeconds(60)))
                .param("app", app)
                .update();
        // outside the window, and expired rows: never due
        String quiet = app + "-q";
        insert(quiet, 60, "NOT_DELIVERED", "FALLBACK_EXPIRED", Duration.ofMinutes(10), null, false, true);
        insert(quiet, 60, "NOT_DELIVERED", "PENDING", Duration.ofMinutes(20), Duration.ofMinutes(6), false, false);

        assertThat(queries.due(app, now, properties.window(), 1000)).isEqualTo(60);
        assertThat(queries.due(quiet, now, properties.window(), 1000)).isZero();
        assertThat(breaker.evaluate(List.of(app, quiet)))
                .hasEntrySatisfying(app, c -> assertThat(c.state()).isEqualTo(CircuitState.OPEN))
                .hasEntrySatisfying(quiet, c -> assertThat(c.state()).isEqualTo(CircuitState.CLOSED));
    }

    @Test
    void smallAppNeverTrips() {
        unmetDue(app, 49, Duration.ofMinutes(10));

        assertThat(queries.due(app, now, properties.window(), 50)).isEqualTo(49);
        assertThat(queries.cohortRatio(app, now, properties.window(), 50)).isNull();
        assertThat(breaker.evaluate(List.of(app)).get(app)).isEqualTo(Circuit.closed(app, Instant.EPOCH));
        assertThat(circuits.find(app)).isEmpty();
        assertThat(events(app)).isEmpty();
    }

    // ---- closing -----------------------------------------------------------------------------------------------

    @Test
    void closesWhenDeliveredResumes() {
        Instant since = now.minus(Duration.ofMinutes(2));
        circuits.transition(new Circuit(app, CircuitState.OPEN, since, null, null, 0),
                new CircuitEvent(0, app, since, "OPENED", "tracking_missing", 50L, null, null, null));
        circuits.addSuppressed(app, 7, now);
        // sends before the open do not count, nor do undelivered ones
        delivered(app, 20, Duration.ofMinutes(3));
        unmetDue(app, 5, Duration.ofSeconds(50));
        delivered(app, 9, Duration.ofMinutes(1));

        assertThat(breaker.evaluate(List.of(app)).get(app).state()).isEqualTo(CircuitState.OPEN);

        delivered(app, 1, Duration.ofSeconds(10));
        Map<String, Circuit> result = breaker.evaluate(List.of(app));

        assertThat(result.get(app).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(result.get(app).suppressed()).isZero();
        assertThat(events(app).get(0)).satisfies(e -> {
            assertThat(e.transition()).isEqualTo("CLOSED");
            assertThat(e.reason()).isEqualTo("delivered_resumed");
            assertThat(e.suppressed()).isEqualTo(7L);
        });
        assertThat(logged("fallback.circuit_closed")).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(EventCapture.data(e))
                    .containsEntry("rp.app_id", app)
                    .containsEntry("reason", "delivered_resumed")
                    .containsEntry("suppressed", 7L);
        });
        assertThat(transitions("CLOSED")).isEqualTo(1);
    }

    @Test
    void staysOpenDuringMinOpen() {
        circuits.transition(new Circuit(app, CircuitState.OPEN, now.minusSeconds(20), null, null, 0),
                new CircuitEvent(0, app, now, "OPENED", "tracking_missing", 50L, null, null, null));
        delivered(app, 15, Duration.ofSeconds(10));

        assertThat(breaker.evaluate(List.of(app)).get(app).state()).isEqualTo(CircuitState.OPEN);
    }

    // ---- concurrency -------------------------------------------------------------------------------------------

    @Test
    void twoWorkersOneTransition() throws Exception {
        unmetDue(app, 60, Duration.ofMinutes(10));
        CyclicBarrier start = new CyclicBarrier(2);
        Runnable worker = () -> {
            try {
                start.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            breaker.evaluate(List.of(app));
        };

        CompletableFuture.allOf(CompletableFuture.runAsync(worker), CompletableFuture.runAsync(worker))
                .get(30, TimeUnit.SECONDS);

        assertThat(events(app)).singleElement().extracting(CircuitEvent::transition).isEqualTo("OPENED");
        assertThat(logged("fallback.circuit_opened")).hasSize(1);
        assertThat(transitions("OPENED")).isEqualTo(1); // once per committed transition
    }

    @Test
    void waitsForTheLockAndSeesTheCommittedState() throws Exception {
        unmetDue(app, 60, Duration.ofMinutes(10));
        CountDownLatch locked = new CountDownLatch(1);
        // another replica holds the lock and opens the circuit; it commits after 500 ms
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(s -> {
            jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", CircuitBreaker.LOCK_KEY).query().listOfRows();
            circuits.transition(new Circuit(app, CircuitState.OPEN, now, null, null, 0),
                    new CircuitEvent(0, app, now, "OPENED", "tracking_missing", 50L, null, null, null));
            locked.countDown();
            sleep(500);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Map<String, Circuit> result = breaker.evaluate(List.of(app));
        holder.get(10, TimeUnit.SECONDS);

        // evaluated after the other replica committed: OPEN, and no second OPENED event
        assertThat(result.get(app).state()).isEqualTo(CircuitState.OPEN);
        assertThat(events(app)).hasSize(1);
        assertThat(logged("fallback.circuit_opened")).isEmpty();
    }

    @Test
    void lockTimeoutFailsTheCheck() throws Exception {
        unmetDue(app, 60, Duration.ofMinutes(10));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
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
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> breaker.evaluate(List.of(app))).isInstanceOf(DataAccessException.class);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        Duration waited = Duration.ofNanos(System.nanoTime() - started);
        // it waited for the lock (the server's timer starts slightly before ours), then gave up
        assertThat(waited).isBetween(CircuitBreaker.LOCK_TIMEOUT.minusMillis(500),
                CircuitBreaker.LOCK_TIMEOUT.plusSeconds(5));
        assertThat(circuits.find(app)).isEmpty();
        assertThat(events(app)).isEmpty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- configuration and operator ----------------------------------------------------------------------------

    @Test
    void disabledNeverOpens() {
        unmetDue(app, 60, Duration.ofMinutes(10));
        String stored = app + "-s";
        circuits.transition(new Circuit(stored, CircuitState.OPEN, now, null, null, 0),
                new CircuitEvent(0, stored, now, "OPENED", "tracking_missing", 50L, null, null, null));

        Map<String, Circuit> result = breaker(disabled()).evaluate(List.of(app, stored));

        assertThat(result).containsOnlyKeys(app, stored);
        assertThat(result.values()).extracting(Circuit::state).containsOnly(CircuitState.CLOSED);
        assertThat(circuits.find(app)).isEmpty();
        assertThat(circuits.find(stored).orElseThrow().state()).isEqualTo(CircuitState.OPEN); // untouched
        assertThat(events(app)).isEmpty();
    }

    @Test
    void forceHoldExpires() {
        unmetDue(app, 60, Duration.ofMinutes(10));
        breaker.evaluate(List.of(app));

        Circuit forced = breaker.force(app, "ops-1");

        assertThat(forced.state()).isEqualTo(CircuitState.FORCED);
        assertThat(forced.forcedBy()).isEqualTo("ops-1");
        assertThat(forced.since()).isEqualTo(now);
        assertThat(forced.forcedUntil()).isEqualTo(now.plus(Duration.ofMinutes(60)));
        assertThat(events(app).get(0)).satisfies(e -> {
            assertThat(e.transition()).isEqualTo("FORCED");
            assertThat(e.reason()).isEqualTo("forced");
            assertThat(e.adminKeyId()).isEqualTo("ops-1");
        });
        assertThat(logged("fallback.circuit_forced")).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(EventCapture.data(e)).containsEntry("rp.app_id", app).containsEntry("rp.admin_key", "ops-1");
        });

        // detection is off while forced: the storm does not reopen it
        assertThat(breaker.evaluate(List.of(app)).get(app).state()).isEqualTo(CircuitState.FORCED);

        clock.advance(Duration.ofMinutes(59));
        assertThat(breaker.evaluate(List.of(app)).get(app).state()).isEqualTo(CircuitState.FORCED);

        clock.advance(Duration.ofMinutes(1)); // forced_until reached
        Map<String, Circuit> result = breaker.evaluate(List.of(app));

        assertThat(result.get(app).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(events(app)).extracting(CircuitEvent::transition)
                .containsExactly("FORCE_ENDED", "FORCED", "OPENED");
        assertThat(events(app).get(0).reason()).isEqualTo("force_expired");
        assertThat(logged("fallback.circuit_closed")).singleElement()
                .satisfies(e -> assertThat(EventCapture.data(e)).containsEntry("reason", "force_expired"));
        assertThat(transitions("OPENED")).isEqualTo(1);
        assertThat(transitions("FORCED")).isEqualTo(1);
        assertThat(transitions("FORCE_ENDED")).isEqualTo(1);
        assertThat(transitions("CLOSED")).isZero();
    }

    // ---- retention ---------------------------------------------------------------------------------------------

    @Test
    void oldEventsArePrunedOnceADay() {
        String old = app + "-r";
        insertEvent(old, now.minus(Duration.ofDays(400)).minusSeconds(60));
        insertEvent(old, now.minus(Duration.ofDays(399)));
        CircuitBreaker fresh = breaker(CircuitBreakerProperties.defaults());

        fresh.evaluate(List.of(app));

        // strictly older than now - 400 days is deleted
        assertThat(events(old)).hasSize(1);

        insertEvent(old, now.minus(Duration.ofDays(500)));
        clock.advance(Duration.ofHours(23));
        fresh.evaluate(List.of(app));

        assertThat(events(old)).hasSize(2); // the next prune runs a day later

        clock.advance(Duration.ofHours(1));
        fresh.evaluate(List.of(app));

        assertThat(events(old)).hasSize(1);
    }

    @Test
    void disabledStillPrunesOldEvents() {
        String old = app + "-r";
        insertEvent(old, now.minus(Duration.ofDays(401)));
        insertEvent(old, now.minus(Duration.ofDays(1)));
        unmetDue(app, 60, Duration.ofMinutes(10));

        breaker(disabled()).evaluate(List.of(app));

        assertThat(events(old)).hasSize(1);
        assertThat(circuits.find(app)).isEmpty(); // detection stays off
        assertThat(events(app)).isEmpty();
    }

    private void insertEvent(String appId, Instant at) {
        jdbc.sql("""
                        insert into fallback_circuit_event (app_id, at, transition, reason)
                        values (:app, :at, 'OPENED', 'tracking_missing')
                        """)
                .param("app", appId)
                .param("at", Timestamp.from(at))
                .update();
    }
}
