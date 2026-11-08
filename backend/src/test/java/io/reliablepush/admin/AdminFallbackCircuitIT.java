package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** The fallback circuit routes of the admin API (spec 6) against a real PostgreSQL, breaker enabled. */
@ActiveProfiles({"api", "worker", "test", "admin"})
@TestPropertySource(properties = {
    "reliable-push.worker.poll-interval=1d", // no worker tick touches the rows seeded here
    "reliable-push.fallback.circuit-breaker.enabled=true" // off in the test profile
})
@ExtendWith(OutputCaptureExtension.class)
class AdminFallbackCircuitIT extends PostgresTestBase {

    static final String SHOP_KEY = "test-admin-key-shop"; // shop, no permission
    static final String ALL_KEY = "test-admin-key-all"; // every app, no fallback-control
    static final String CTL_KEY = "test-admin-key-ctl"; // shop, fallback-control
    private static final long LOCK_KEY = 4_242_002L; // the detector's advisory lock
    private static final Duration FORCE_LOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final String LIST = "/v1/admin/fallback-circuits";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.sql("delete from fallback_circuit_event where app_id in ('shop', 'other')").update();
        jdbc.sql("delete from fallback_circuit where app_id in ('shop', 'other')").update();
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String key) {
        return http.method(method)
                .uri(path)
                .headers(h -> {
                    if (key != null) {
                        h.set("X-Admin-Key", key);
                    }
                })
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String path, String key) {
        return call(HttpMethod.GET, path, key);
    }

    private ResponseEntity<String> resume(String appId, String key) {
        return call(HttpMethod.POST, LIST + "/" + appId + ":resume", key);
    }

    private void seedCircuit(String appId, String state, Instant since, long suppressed) {
        jdbc.sql("insert into fallback_circuit (app_id, state, since, suppressed) values (:app, :state, :since, :n)")
                .param("app", appId)
                .param("state", state)
                .param("since", Timestamp.from(since))
                .param("n", suppressed)
                .update();
    }

    private void seedEvent(String appId, Instant at, String transition, String reason) {
        jdbc.sql("""
                        insert into fallback_circuit_event (app_id, at, transition, reason)
                        values (:app, :at, :transition, :reason)
                        """)
                .param("app", appId)
                .param("at", Timestamp.from(at))
                .param("transition", transition)
                .param("reason", reason)
                .update();
    }

    @Test
    void listShowsAbsentAppsAsClosedWithinTheKeysScope() {
        ResponseEntity<String> response = get(LIST, CTL_KEY);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        String body = response.getBody();
        assertThat(JsonPath.<Boolean>read(body, "$.suspended")).isFalse();
        assertThat(JsonPath.<Boolean>read(body, "$.enabled")).isTrue();
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].appId")).containsExactly("shop");
        assertThat(JsonPath.<String>read(body, "$.items[0].state")).isEqualTo("CLOSED");
        assertThat(JsonPath.<Object>read(body, "$.items[0].since")).isNull();
        assertThat(JsonPath.<Object>read(body, "$.items[0].forcedUntil")).isNull();
        assertThat(JsonPath.<Object>read(body, "$.items[0].forcedBy")).isNull();
        assertThat(JsonPath.<Integer>read(body, "$.items[0].suppressed")).isZero();
        OpenApiAssert.assertMatchesContract("GET", LIST, response);
    }

    @Test
    void listShowsStoredCircuitsOfEveryAppForAnAllAppsKey() {
        Instant since = Instant.parse("2026-11-01T10:00:00Z");
        seedCircuit("other", "OPEN", since, 7);

        ResponseEntity<String> response = get(LIST, ALL_KEY);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        List<String> apps = JsonPath.read(body, "$.items[*].appId");
        assertThat(apps).isSorted().contains("shop", "other");
        assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.appId=='other')].state")).containsExactly("OPEN");
        assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.appId=='other')].since"))
                .containsExactly("2026-11-01T10:00:00Z");
        assertThat(JsonPath.<List<Integer>>read(body, "$.items[?(@.appId=='other')].suppressed")).containsExactly(7);
        assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.appId=='shop')].state")).containsExactly("CLOSED");
        OpenApiAssert.assertMatchesContract("GET", LIST, response);
    }

    @Test
    void listWithoutAKeyIs401() {
        ResponseEntity<String> response = get(LIST, null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        OpenApiAssert.assertMatchesContract("GET", LIST, response);
    }

    @Test
    void eventsPageNewestFirst() {
        Instant t = Instant.parse("2026-11-01T10:00:00Z");
        seedEvent("shop", t, "OPENED", "tracking_missing");
        seedEvent("shop", t.plusSeconds(60), "CLOSED", "delivered_resumed");
        seedEvent("shop", t.plusSeconds(120), "OPENED", "tracking_missing");
        seedEvent("other", t, "OPENED", "tracking_missing");
        String path = LIST + "/shop/events";

        ResponseEntity<String> first = get(path + "?limit=2", SHOP_KEY);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(first.getBody(), "$.items[*].at"))
                .containsExactly("2026-11-01T10:02:00Z", "2026-11-01T10:01:00Z");
        assertThat(JsonPath.<String>read(first.getBody(), "$.items[0].transition")).isEqualTo("OPENED");
        assertThat(JsonPath.<String>read(first.getBody(), "$.items[1].reason")).isEqualTo("delivered_resumed");
        String cursor = JsonPath.read(first.getBody(), "$.nextCursor");
        assertThat(cursor).isNotBlank();
        OpenApiAssert.assertMatchesContract("GET", path, first);

        ResponseEntity<String> second = get(path + "?limit=2&cursor=" + cursor, SHOP_KEY);

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(second.getBody(), "$.items[*].at")).containsExactly("2026-11-01T10:00:00Z");
        assertThat(JsonPath.<Object>read(second.getBody(), "$.nextCursor")).isNull();
        OpenApiAssert.assertMatchesContract("GET", path, second);

        ResponseEntity<String> all = get(path, SHOP_KEY); // default limit
        assertThat(JsonPath.<List<Object>>read(all.getBody(), "$.items")).hasSize(3);
        OpenApiAssert.assertMatchesContract("GET", path, all);
    }

    @Test
    void eventsRejectBadParametersAndForeignApps() {
        String path = LIST + "/shop/events";
        for (String query : List.of("?limit=0", "?limit=201", "?limit=abc", "?cursor=not-a-cursor", "?cursor=")) {
            ResponseEntity<String> bad = get(path + query, SHOP_KEY);
            assertThat(bad.getStatusCode().value()).as(query).isEqualTo(400);
            OpenApiAssert.assertMatchesContract("GET", path, bad);
        }
        assertThat(get(path + "?limit=200", SHOP_KEY).getStatusCode().value()).isEqualTo(200);

        String other = LIST + "/other/events"; // outside the scope of the shop key, same as an unknown app
        ResponseEntity<String> outside = get(other, SHOP_KEY);
        assertThat(outside.getStatusCode().value()).isEqualTo(404);
        OpenApiAssert.assertMatchesContract("GET", other, outside);
        String unknown = LIST + "/nope/events";
        ResponseEntity<String> missing = get(unknown, ALL_KEY);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        OpenApiAssert.assertMatchesContract("GET", unknown, missing);
    }

    @Test
    void resumeForcesTheCircuitAndAuditsWithoutTheKey(CapturedOutput output) {
        Instant before = Instant.now();
        seedCircuit("shop", "OPEN", before.minusSeconds(600), 5);

        ResponseEntity<String> response = resume("shop", CTL_KEY);
        Instant after = Instant.now();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.appId")).isEqualTo("shop");
        assertThat(JsonPath.<String>read(body, "$.state")).isEqualTo("FORCED");
        assertThat(JsonPath.<String>read(body, "$.forcedBy")).isEqualTo("admin-ctl");
        assertThat(Instant.parse(JsonPath.read(body, "$.forcedUntil")))
                .isBetween(before.plus(Duration.ofMinutes(60)), after.plus(Duration.ofMinutes(60)));
        assertThat(Instant.parse(JsonPath.read(body, "$.since"))).isBetween(before, after);
        assertThat(JsonPath.<Integer>read(body, "$.suppressed")).isZero();
        OpenApiAssert.assertMatchesContract("POST", LIST + "/shop:resume", response);

        Map<String, Object> row = jdbc.sql("select state, forced_by from fallback_circuit where app_id = 'shop'")
                .query().singleRow();
        assertThat(row).containsEntry("state", "FORCED").containsEntry("forced_by", "admin-ctl");
        Map<String, Object> event = jdbc.sql("""
                        select transition, reason, admin_key_id, suppressed from fallback_circuit_event
                        where app_id = 'shop'
                        """)
                .query().singleRow();
        assertThat(event)
                .containsEntry("transition", "FORCED")
                .containsEntry("admin_key_id", "admin-ctl")
                .containsEntry("suppressed", 5L);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut().lines())
                .anySatisfy(line -> {
                    assertThat(line).contains("\"admin.fallback_resumed\"");
                    assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo("shop");
                    assertThat(JsonPath.<String>read(line, "$['rp.admin_key']")).isEqualTo("admin-ctl");
                }));
        assertThat(output.getAll()).doesNotContain(CTL_KEY);
    }

    @Test
    void resumeOfAClosedCircuitForcesItAndResumeOfAForcedOneRestartsTheHold() {
        ResponseEntity<String> first = resume("shop", CTL_KEY); // no row at all: CLOSED
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<String>read(first.getBody(), "$.state")).isEqualTo("FORCED");
        Instant firstUntil = Instant.parse(JsonPath.read(first.getBody(), "$.forcedUntil"));

        ResponseEntity<String> second = resume("shop", CTL_KEY);

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<String>read(second.getBody(), "$.state")).isEqualTo("FORCED");
        assertThat(Instant.parse(JsonPath.read(second.getBody(), "$.forcedUntil"))).isAfter(firstUntil);
        OpenApiAssert.assertMatchesContract("POST", LIST + "/shop:resume", second);
        assertThat(jdbc.sql("select count(*) from fallback_circuit_event where app_id = 'shop' and transition = 'FORCED'")
                        .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void resumeWithoutThePermissionIs403AndChangesNothing() {
        seedCircuit("shop", "OPEN", Instant.now().minusSeconds(60), 3);

        for (String key : List.of(SHOP_KEY, ALL_KEY)) {
            ResponseEntity<String> response = resume("shop", key);
            assertThat(response.getStatusCode().value()).as(key).isEqualTo(403);
            OpenApiAssert.assertMatchesContract("POST", LIST + "/shop:resume", response);
        }
        assertThat(jdbc.sql("select state from fallback_circuit where app_id = 'shop'").query(String.class).single())
                .isEqualTo("OPEN");
        assertThat(jdbc.sql("select count(*) from fallback_circuit_event").query(Long.class).single()).isZero();
    }

    @Test
    void resumeOfAnAppOutsideTheScopeOrUnknownIs404() {
        for (String app : List.of("other", "nope")) {
            ResponseEntity<String> response = resume(app, CTL_KEY);
            assertThat(response.getStatusCode().value()).as(app).isEqualTo(404);
            OpenApiAssert.assertMatchesContract("POST", LIST + "/" + app + ":resume", response);
        }
        assertThat(jdbc.sql("select count(*) from fallback_circuit").query(Long.class).single()).isZero();
    }

    @Test
    void resumeGivesUpWithin503WhenAnEvaluationHoldsTheLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(s -> {
            jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", LOCK_KEY).query().listOfRows();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        ResponseEntity<String> response;
        try {
            response = resume("shop", CTL_KEY);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        Duration waited = Duration.ofNanos(System.nanoTime() - started);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(waited).isBetween(FORCE_LOCK_TIMEOUT.minusMillis(500),
                FORCE_LOCK_TIMEOUT.plusSeconds(5));
        OpenApiAssert.assertMatchesContract("POST", LIST + "/shop:resume", response);
        assertThat(jdbc.sql("select count(*) from fallback_circuit").query(Long.class).single()).isZero();
    }

    @Test
    void meListsTheFallbackControlPermission() {
        ResponseEntity<String> ctl = get("/v1/admin/me", CTL_KEY);
        assertThat(JsonPath.<List<String>>read(ctl.getBody(), "$.permissions"))
                .containsExactlyInAnyOrder("payload", "fallback-control");
        OpenApiAssert.assertMatchesContract("GET", "/v1/admin/me", ctl);

        ResponseEntity<String> shop = get("/v1/admin/me", SHOP_KEY);
        assertThat(JsonPath.<List<String>>read(shop.getBody(), "$.permissions")).isEmpty();
    }
}
