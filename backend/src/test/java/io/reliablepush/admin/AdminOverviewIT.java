package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Overview route against a real PostgreSQL. No worker role runs in this context, but contexts of other ITs stay cached
 * with their schedulers: the due row of the backlog test is therefore row-locked (SKIP LOCKED skips it) while it is read.
 */
@ActiveProfiles({"api", "test", "admin"})
class AdminOverviewIT extends PostgresTestBase {

    private static final String SHOP_KEY = "test-admin-key-shop";
    private static final String ALL_KEY = "test-admin-key-all";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void cleanApps() {
        // the database is shared between ITs; the overview counts whole apps, so these two start empty
        jdbc.sql("delete from notification where app_id in ('shop', 'other')").update();
    }

    private ResponseEntity<String> get(String key, String path) {
        ResponseEntity<String> response = http.get()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Admin-Key", key)
                .retrieve()
                .onStatus(status -> true, (request, resp) -> {})
                .toEntity(String.class);
        OpenApiAssert.assertMatchesContract("GET", path.contains("?") ? path.substring(0, path.indexOf('?')) : path, response);
        return response;
    }

    private UUID notification(
            String app, String state, Instant createdAt, Instant dueAt, boolean delivered, boolean read) {
        UUID id = UUID.randomUUID();
        boolean closed = !java.util.List.of("PENDING", "DELIVERED", "DISPATCHING").contains(state);
        jdbc.sql("""
                        insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload,
                            state, created_at, due_at, delivered_at, read_at, closed_at, next_attempt_at)
                        values (:id, :app, :user, 'NOT_DELIVERED', 5, cast('{}' as jsonb), :state, :created, :due,
                            :delivered, :read, :closed, :next)
                        """)
                .param("id", id)
                .param("app", app)
                .param("user", "u-" + id)
                .param("state", state)
                .param("created", Timestamp.from(createdAt))
                .param("due", dueAt == null ? null : Timestamp.from(dueAt))
                .param("delivered", delivered ? Timestamp.from(createdAt.plusSeconds(5)) : null)
                .param("read", read ? Timestamp.from(createdAt.plusSeconds(9)) : null)
                .param("closed", closed ? Timestamp.from(createdAt.plusSeconds(20)) : null)
                .param("next", "DISPATCHING".equals(state) ? Timestamp.from(createdAt.plus(1, ChronoUnit.HOURS).plus(1, ChronoUnit.DAYS)) : null)
                .update();
        return id;
    }

    @Test
    void countsAndRatesInWindow() {
        Instant now = Instant.now();
        Instant recent = now.minus(20, ChronoUnit.MINUTES);
        notification("shop", "CANCELLED", recent, null, false, false);
        notification("shop", "DELIVERED", recent, now.plus(1, ChronoUnit.HOURS), true, false);
        notification("shop", "READ", recent, null, true, true);
        notification("shop", "FALLBACK_TRIGGERED", recent, null, false, false);
        notification("shop", "FALLBACK_TRIGGERED", now.minus(2, ChronoUnit.HOURS), null, false, false);

        ResponseEntity<String> response = get(ALL_KEY, "/v1/admin/overview?app=shop&window=1h");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        String body = response.getBody();
        assertThat((String) JsonPath.read(body, "$.items[0].appId")).isEqualTo("shop");
        assertThat((String) JsonPath.read(body, "$.items[0].window")).isEqualTo("1h");
        assertThat((Integer) JsonPath.read(body, "$.items[0].registered")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(body, "$.items[0].cancelled")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.items[0].delivered")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(body, "$.items[0].read")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.items[0].fallbackTriggered")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.items[0].fallbackFailed")).isZero();
        assertThat((Integer) JsonPath.read(body, "$.items[0].fallbackExpired")).isZero();
        assertThat((Double) JsonPath.read(body, "$.items[0].deliveryRate")).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat((Double) JsonPath.read(body, "$.items[0].readRate")).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat((Double) JsonPath.read(body, "$.items[0].fallbackRate")).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));

        // the older row is inside a wider window
        String wide = get(ALL_KEY, "/v1/admin/overview?app=shop&window=24h").getBody();
        assertThat((Integer) JsonPath.read(wide, "$.items[0].registered")).isEqualTo(5);
    }

    @Test
    void backlogNow() {
        Instant now = Instant.now();
        notification("shop", "PENDING", now, now.plus(1, ChronoUnit.HOURS), false, false);
        // a claimed row keeps its due_at (claimDue never clears it)
        notification("shop", "DISPATCHING", now, now.minus(1, ChronoUnit.MINUTES), false, false);
        // delivered under NOT_DELIVERED: closed, state stays DELIVERED, due_at cleared; in no backlog count
        notification("shop", "DELIVERED", now.minus(5, ChronoUnit.MINUTES), null, true, false);
        jdbc.sql("update notification set closed_at = :c where app_id = 'shop' and state = 'DELIVERED'")
                .param("c", Timestamp.from(now.minus(4, ChronoUnit.MINUTES)))
                .update();
        // due in one second: a cached worker context cannot claim it before the lock below is held
        UUID due = notification("shop", "PENDING", now.minus(10, ChronoUnit.SECONDS), now.plus(1, ChronoUnit.SECONDS), false, false);

        String body = transactions.execute(status -> {
            jdbc.sql("select id from notification where id = :id for update").param("id", due).query().listOfRows();
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return get(ALL_KEY, "/v1/admin/overview?app=shop").getBody();
        });

        assertThat((Integer) JsonPath.read(body, "$.items[0].now.pending")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.items[0].now.dueUnclaimed")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.items[0].now.dispatching")).isEqualTo(1);
        assertThat(((Number) JsonPath.read(body, "$.items[0].now.oldestDueAgeSeconds")).longValue()).isGreaterThanOrEqualTo(0);

        jdbc.sql("delete from notification where id = :id").param("id", due).update();
        String none = get(ALL_KEY, "/v1/admin/overview?app=shop").getBody();
        assertThat((Integer) JsonPath.read(none, "$.items[0].now.dueUnclaimed")).isZero();
        assertThat((Object) JsonPath.read(none, "$.items[0].now.oldestDueAgeSeconds")).isNull();
    }

    @Test
    void backlogUsesTheDueIndexNotTheClosedHistory() {
        // a large closed history (delivered under NOT_DELIVERED: state DELIVERED, due_at null) and a few open rows
        jdbc.sql("""
                        insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload,
                            state, created_at, delivered_at, closed_at)
                        select gen_random_uuid(), 'shop', 'u-' || g, 'NOT_DELIVERED', 5, cast('{}' as jsonb),
                               'DELIVERED', now() - interval '1 day', now() - interval '1 day', now() - interval '1 day'
                        from generate_series(1, 20000) g
                        """)
                .update();
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            notification("shop", "PENDING", now, now.plus(1, ChronoUnit.HOURS), false, false);
        }
        jdbc.sql("analyze notification").update();

        String plan = String.join("\n", jdbc.sql("explain " + OverviewQuery.BACKLOG)
                .param("apps", new String[] {"shop"})
                .param("now", Timestamp.from(now))
                .query(String.class)
                .list());

        assertThat(plan).contains("notification_due");
        String body = get(ALL_KEY, "/v1/admin/overview?app=shop").getBody();
        assertThat((Integer) JsonPath.read(body, "$.items[0].now.pending")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(body, "$.items[0].now.dueUnclaimed")).isZero();
        assertThat((Integer) JsonPath.read(body, "$.items[0].now.dispatching")).isZero();
    }

    @Test
    void emptyAppIsZeros() {
        String body = get(ALL_KEY, "/v1/admin/overview?app=other").getBody();

        assertThat((Integer) JsonPath.read(body, "$.items.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(body, "$.items[0].window")).isEqualTo("24h");
        for (String field : new String[] {"registered", "cancelled", "delivered", "read", "fallbackTriggered", "fallbackFailed", "fallbackExpired"}) {
            assertThat((Integer) JsonPath.read(body, "$.items[0]." + field)).as(field).isZero();
        }
        for (String field : new String[] {"deliveryRate", "readRate", "fallbackRate"}) {
            assertThat((Object) JsonPath.read(body, "$.items[0]." + field)).as(field).isNull();
        }
        assertThat((Integer) JsonPath.read(body, "$.items[0].now.pending")).isZero();
        assertThat((Object) JsonPath.read(body, "$.items[0].now.oldestDueAgeSeconds")).isNull();
    }

    @Test
    void windowValidationAndVisibility() {
        assertThat(get(ALL_KEY, "/v1/admin/overview?app=shop&window=2h").getStatusCode().value()).isEqualTo(400);
        assertThat(get(ALL_KEY, "/v1/admin/overview?app=shop&window=").getStatusCode().value()).isEqualTo(400);

        String shopOnly = get(SHOP_KEY, "/v1/admin/overview").getBody();
        assertThat((Integer) JsonPath.read(shopOnly, "$.items.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(shopOnly, "$.items[0].appId")).isEqualTo("shop");

        assertThat(get(SHOP_KEY, "/v1/admin/overview?app=other").getStatusCode().value()).isEqualTo(404);

        String all = get(ALL_KEY, "/v1/admin/overview").getBody();
        java.util.List<String> ids = JsonPath.read(all, "$.items[*].appId");
        assertThat(ids).isSorted().contains("other", "shop");
    }
}
