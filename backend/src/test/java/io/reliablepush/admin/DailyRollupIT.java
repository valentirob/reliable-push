package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.notifications.RetentionJob;
import io.reliablepush.shared.logging.EventCapture;
import io.reliablepush.support.MutableClock;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily rollups, the retention guard and the trends route against a real PostgreSQL. The clock is fixed at
 * 2026-11-06T10:00Z (window 2026-10-30 .. 2026-11-05). All rows are closed or not due, so no worker of a cached context
 * can claim them. The rollup cron is a real expression only so that the guard is active; it never fires (03:07 UTC).
 */
@ActiveProfiles({"api", "worker", "test", "admin"})
@TestPropertySource(properties = {"reliable-push.admin.rollup.cron=0 7 3 * * *", "reliable-push.worker.poll-interval=1d"})
@Import(DailyRollupIT.Fakes.class)
class DailyRollupIT extends PostgresTestBase {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final String ALL_KEY = "test-admin-key-all";

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    DailyRollupJob job;

    @Autowired
    RetentionJob retention;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    MutableClock clock;

    private EventCapture capture;

    @BeforeEach
    void clean() {
        clock.set(NOW);
        jdbc.sql("delete from notification where app_id in ('shop', 'other')").update();
        jdbc.sql("delete from daily_stats").update();
        capture = new EventCapture();
    }

    @AfterEach
    void stop() {
        capture.close();
    }

    private UUID device(String app, String platform) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at)
                        values (:id, :app, 'u', :platform, 1, now(), now())
                        """)
                .param("id", id)
                .param("app", app)
                .param("platform", platform)
                .update();
        return id;
    }

    private UUID row(String app, String state, Instant created, Long deliveredAfterSec, Long readAfterSec) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload,
                            state, created_at, delivered_at, read_at, closed_at)
                        values (:id, :app, :user, 'NOT_DELIVERED', 5, cast('{}' as jsonb), :state, :created,
                            :delivered, :read, :created)
                        """)
                .param("id", id)
                .param("app", app)
                .param("user", "u-" + id)
                .param("state", state)
                .param("created", Timestamp.from(created))
                .param("delivered", deliveredAfterSec == null ? null : Timestamp.from(created.plusSeconds(deliveredAfterSec)))
                .param("read", readAfterSec == null ? null : Timestamp.from(created.plusSeconds(readAfterSec)))
                .update();
        return id;
    }

    private void event(UUID notification, UUID device, String type) {
        jdbc.sql("""
                        insert into notification_event (notification_id, device_id, type, occurred_at, received_at)
                        values (:n, :d, :t, now(), now())
                        """)
                .param("n", notification)
                .param("d", device)
                .param("t", type)
                .update();
    }

    private Map<String, Object> stats(String app, String day) {
        return jdbc.sql("select * from daily_stats where app_id = :a and day = cast(:d as date)")
                .param("a", app)
                .param("d", day)
                .query()
                .singleRow();
    }

    private List<LogEvent> events(String action) {
        return List.copyOf(capture.events()).stream()
                .filter(e -> action.equals(EventCapture.data(e).get("event.action")))
                .toList();
    }

    @Test
    void upsertsWindowWithZeroRows() throws Exception {
        row("shop", "FALLBACK_TRIGGERED", Instant.parse("2026-11-03T10:00:00Z"), null, null);
        row("shop", "CANCELLED", Instant.parse("2026-11-04T10:00:00Z"), null, null);

        assertThat(job.run()).isEqualTo(7);

        long apps = jdbc.sql("select count(distinct app_id) from daily_stats").query(Long.class).single();
        long rows = jdbc.sql("select count(*) from daily_stats").query(Long.class).single();
        assertThat(rows).isEqualTo(apps * 7);
        assertThat(jdbc.sql("select min(day)::text from daily_stats").query(String.class).single())
                .isEqualTo("2026-10-30");
        assertThat(jdbc.sql("select max(day)::text from daily_stats").query(String.class).single())
                .isEqualTo("2026-11-05");
        assertThat(stats("shop", "2026-11-03")).containsEntry("registered", 1).containsEntry("fallback_triggered", 1);
        assertThat(stats("shop", "2026-11-04")).containsEntry("cancelled", 1);
        Map<String, Object> zeros = stats("other", "2026-11-03");
        assertThat(zeros).containsEntry("registered", 0).containsEntry("delivered", 0);
        assertThat(zeros.get("delivery_p50_ms")).isNull();
        assertThat(events("rollup.completed")).hasSize(1);
        assertThat(EventCapture.data(events("rollup.completed").get(0))).containsEntry("count", 7);

        Object before = stats("shop", "2026-11-03").get("computed_at");
        clock.advance(Duration.ofSeconds(5));
        assertThat(job.run()).isEqualTo(7);
        Map<String, Object> after = stats("shop", "2026-11-03");
        assertThat(after.get("computed_at")).isNotEqualTo(before);
        assertThat(after).containsEntry("registered", 1);
    }

    @Test
    void dayBoundary() {
        row("shop", "FALLBACK_TRIGGERED", Instant.parse("2026-11-04T23:59:59.999Z"), null, null);
        row("shop", "FALLBACK_TRIGGERED", Instant.parse("2026-11-05T00:00:00Z"), null, null);

        job.run();

        assertThat(stats("shop", "2026-11-04")).containsEntry("registered", 1);
        assertThat(stats("shop", "2026-11-05")).containsEntry("registered", 1);
    }

    @Test
    void countsMatchDefinitions() {
        UUID ios = device("shop", "IOS");
        UUID android = device("shop", "ANDROID");
        Instant day = Instant.parse("2026-11-04T08:00:00Z");
        // READ-only: delivered_at set by the lifecycle; platform from the READ device
        UUID readOnly = row("shop", "READ", day, 10L, 10L);
        event(readOnly, android, "READ");
        // DELIVERED then READ on different devices: the DELIVERED device counts
        UUID both = row("shop", "READ", day, 20L, 30L);
        event(both, ios, "DELIVERED");
        event(both, android, "READ");
        // delivered whose device is gone: delivered but in neither platform
        row("shop", "DELIVERED", day, 40L, null);
        // 2026-11-02: nothing delivered
        row("shop", "FALLBACK_FAILED", Instant.parse("2026-11-02T08:00:00Z"), null, null);
        row("shop", "FALLBACK_EXPIRED", Instant.parse("2026-11-02T09:00:00Z"), null, null);

        job.run();

        Map<String, Object> s = stats("shop", "2026-11-04");
        assertThat(s).containsEntry("registered", 3).containsEntry("delivered", 3).containsEntry("read", 2);
        assertThat(s).containsEntry("delivered_ios", 1).containsEntry("delivered_android", 1);
        assertThat(((Number) s.get("delivery_p50_ms")).longValue()).isEqualTo(20_000L);
        assertThat(((Number) s.get("delivery_p95_ms")).longValue()).isEqualTo(38_000L);
        Map<String, Object> none = stats("shop", "2026-11-02");
        assertThat(none).containsEntry("fallback_failed", 1).containsEntry("fallback_expired", 1);
        assertThat(none.get("delivery_p50_ms")).isNull();
        assertThat(none.get("delivery_p95_ms")).isNull();
    }

    @Test
    void lockMakesSecondRunANoop() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            jdbc.sql("select pg_advisory_xact_lock(4242001)").query().listOfRows();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            int written = CompletableFuture.supplyAsync(job::run).get(20, TimeUnit.SECONDS);
            assertThat(written).isZero();
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.sql("select count(*) from daily_stats").query(Long.class).single()).isZero();
        assertThat(events("rollup.completed")).isEmpty();
    }

    @Test
    void oldRollupsDeleted() {
        jdbc.sql("""
                        insert into daily_stats values ('2025-09-01', 'shop', 1, 0, 0, 0, 0, 0, 0, 0, 0, null, null, now()),
                                                       ('2025-10-02', 'shop', 1, 0, 0, 0, 0, 0, 0, 0, 0, null, null, now())
                        """)
                .update();

        job.run();

        // 2026-11-06 minus 400 days = 2025-10-02: strictly older is deleted
        assertThat(jdbc.sql("select count(*) from daily_stats where day < '2025-10-02'").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from daily_stats where day = '2025-10-02'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void retentionSkipsWithoutRecentRollup() {
        UUID old = row("shop", "READ", Instant.parse("2026-01-01T00:00:00Z"), 1L, 1L);
        jdbc.sql("update notification set closed_at = :c where id = :id")
                .param("c", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")))
                .param("id", old)
                .update();

        assertThat(retention.purge()).isZero();

        assertThat(jdbc.sql("select count(*) from notification where id = :id").param("id", old).query(Long.class).single())
                .isEqualTo(1);
        List<LogEvent> skipped = events("retention.skipped");
        assertThat(skipped).hasSize(1);
        assertThat(EventCapture.data(skipped.get(0))).containsEntry("reason", "rollup_missing");
        assertThat(skipped.get(0).getLevel()).isEqualTo(org.apache.logging.log4j.Level.WARN);

        job.run();
        assertThat(retention.purge()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select count(*) from notification where id = :id").param("id", old).query(Long.class).single())
                .isZero();

        // a rollup older than 20 hours no longer counts
        clock.advance(Duration.ofHours(21));
        assertThat(retention.purge()).isZero();
        assertThat(events("retention.skipped")).hasSize(2);
    }

    /** Holds the rollup lock in another connection until {@code release}, then commits a fresh rollup row. */
    private CompletableFuture<Void> runningRollup(CountDownLatch locked, CountDownLatch release, boolean commitFresh) {
        return CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", DailyRollupJob.LOCK_KEY).query().listOfRows();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (commitFresh) {
                jdbc.sql("""
                                insert into daily_stats
                                values ('2026-11-05', 'shop', 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, :at)
                                """)
                        .param("at", Timestamp.from(NOW))
                        .update();
            }
        }));
    }

    private long waitingForAdvisoryLock() {
        return jdbc.sql("select count(*) from pg_locks where locktype = 'advisory' and not granted")
                .query(Long.class)
                .single();
    }

    @Test
    void retentionWaitsForARunningRollup() throws Exception {
        UUID old = row("shop", "READ", Instant.parse("2026-01-01T00:00:00Z"), 1L, 1L);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> rollup = runningRollup(locked, release, true);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Integer> purge;
        try {
            purge = CompletableFuture.supplyAsync(retention::purge);
            // the guard waits on the lock instead of reading the stale (here: missing) computed_at at once
            await().atMost(Duration.ofSeconds(10)).until(() -> waitingForAdvisoryLock() > 0 || purge.isDone());
            assertThat(purge).isNotDone();
        } finally {
            release.countDown();
            rollup.get(10, TimeUnit.SECONDS);
        }

        assertThat(purge.get(10, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select count(*) from notification where id = :id").param("id", old).query(Long.class).single())
                .isZero();
        assertThat(events("retention.skipped")).isEmpty();
    }

    @Autowired
    AdminProperties adminProperties;

    @Autowired
    DailyStatsRepository dailyStats;

    @Test
    void retentionSkipsWhileTheRollupOutlastsTheWait() throws Exception {
        RollupRetentionGuard guard =
                new RollupRetentionGuard(adminProperties, dailyStats, clock, transactions, Duration.ofSeconds(1));
        jdbc.sql("""
                        insert into daily_stats values ('2026-11-04', 'shop', 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, :at)
                        """)
                .param("at", Timestamp.from(NOW))
                .update();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> rollup = runningRollup(locked, release, false);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(guard.veto()).contains("rollup_running");
        } finally {
            release.countDown();
            rollup.get(10, TimeUnit.SECONDS);
        }
        // lock free again and a fresh row: purge allowed
        assertThat(guard.veto()).isEmpty();
    }

    private ResponseEntity<String> get(String path) {
        ResponseEntity<String> response = http.get()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Admin-Key", ALL_KEY)
                .retrieve()
                .onStatus(status -> true, (request, resp) -> {})
                .toEntity(String.class);
        OpenApiAssert.assertMatchesContract(
                "GET", path.contains("?") ? path.substring(0, path.indexOf('?')) : path, response);
        return response;
    }

    @SuppressWarnings("unchecked")
    private <T> T first(String body, String path) {
        List<T> found = JsonPath.read(body, path);
        return found.get(0);
    }

    @Test
    void trendsRoute() {
        row("shop", "READ", Instant.parse("2026-11-04T08:00:00Z"), 5L, 6L);
        row("shop", "FALLBACK_TRIGGERED", Instant.parse("2026-11-06T08:00:00Z"), null, null);
        // older than the window: only stored
        jdbc.sql("""
                        insert into daily_stats values ('2026-10-20', 'shop', 4, 0, 2, 1, 1, 1, 1, 0, 0, 1500, 2500, now())
                        """)
                .update();
        // stale stored row for a window day: the live number wins
        jdbc.sql("""
                        insert into daily_stats values ('2026-11-04', 'shop', 99, 0, 0, 0, 0, 0, 0, 0, 0, null, null, now())
                        """)
                .update();

        ResponseEntity<String> response = get("/v1/admin/stats/daily?app=shop&from=2026-10-20&to=2026-11-07");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.appId")).isEqualTo("shop");
        List<String> days = JsonPath.read(body, "$.days[*].day");
        assertThat(days).first().isEqualTo("2026-10-20");
        assertThat(days).contains("2026-10-30", "2026-11-04", "2026-11-06").doesNotContain("2026-10-21");
        assertThat(days).isSorted();
        assertThat(this.<Boolean>first(body, "$.days[?(@.day=='2026-10-20')].live")).isFalse();
        assertThat(this.<Integer>first(body, "$.days[?(@.day=='2026-10-20')].registered")).isEqualTo(4);
        assertThat(this.<Double>first(body, "$.days[?(@.day=='2026-10-20')].deliveryRate")).isEqualTo(0.5);
        assertThat(this.<Boolean>first(body, "$.days[?(@.day=='2026-11-04')].live")).isTrue();
        assertThat(this.<Integer>first(body, "$.days[?(@.day=='2026-11-04')].registered")).isEqualTo(1);
        assertThat(this.<Integer>first(body, "$.days[?(@.day=='2026-11-04')].read")).isEqualTo(1);
        assertThat(this.<Boolean>first(body, "$.days[?(@.day=='2026-11-06')].live")).isTrue();
        assertThat(this.<Integer>first(body, "$.days[?(@.day=='2026-11-06')].fallbackTriggered")).isEqualTo(1);
        assertThat(this.<Object>first(body, "$.days[?(@.day=='2026-11-05')].deliveryRate")).isNull();
    }

    @Test
    void trendsValidationAndScope() {
        assertThat(get("/v1/admin/stats/daily?app=shop&from=2025-10-01&to=2026-11-07").getStatusCode().value())
                .isEqualTo(400); // 402 days
        assertThat(get("/v1/admin/stats/daily?app=shop&from=2026-11-05&to=2026-11-05").getStatusCode().value())
                .isEqualTo(400);
        assertThat(get("/v1/admin/stats/daily?app=shop&from=nope").getStatusCode().value()).isEqualTo(400);
        assertThat(get("/v1/admin/stats/daily").getStatusCode().value()).isEqualTo(400);
        assertThat(get("/v1/admin/stats/daily?app=ghost").getStatusCode().value()).isEqualTo(404);
        ResponseEntity<String> byDefault = get("/v1/admin/stats/daily?app=shop");
        assertThat(byDefault.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(byDefault.getBody(), "$.days.length()")).isEqualTo(7 + 1);
        assertThat(LocalDate.parse(JsonPath.read(byDefault.getBody(), "$.days[7].day"))).isEqualTo(LocalDate.parse("2026-11-06"));
    }
}
