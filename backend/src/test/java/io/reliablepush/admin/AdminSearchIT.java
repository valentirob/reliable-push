package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.fallback.FallbackAttempt;
import io.reliablepush.fallback.FallbackAttemptRepository;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Support-lookup routes of the admin API against a real PostgreSQL. The database is shared: every test uses fresh ids. */
@ActiveProfiles({"api", "worker", "test", "admin"})
@ExtendWith(OutputCaptureExtension.class)
class AdminSearchIT extends PostgresTestBase {

    private static final String SHOP_KEY = "test-admin-key-shop";
    private static final String ALL_KEY = "test-admin-key-all";
    private static final String PAYLOAD_JSON = "{\"sms\":\"code 123456\"}";
    private static final Instant BASE = Instant.parse("2026-03-01T10:00:00Z");
    /**
     * Due time of every open row: far in the future, so no worker (this context runs one, and cached contexts of other
     * ITs keep theirs) ever claims a seeded PENDING row and turns it into FALLBACK_PENDING mid-test.
     */
    private static final Instant NEVER_DUE = Instant.parse("2999-01-01T00:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FallbackAttemptRepository attempts;

    private ResponseEntity<String> get(String key, String path) {
        // the path is already percent-encoded: a URI instance is used as is
        return http.get()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Admin-Key", key)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private ResponseEntity<String> checked(String key, String path) {
        ResponseEntity<String> response = get(key, path);
        String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        OpenApiAssert.assertMatchesContract("GET", bare, response);
        return response;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String user() {
        return "u-" + UUID.randomUUID();
    }

    private UUID notification(String app, String user, String externalId, String state, Instant createdAt) {
        UUID id = UUID.randomUUID();
        boolean closed = !List.of("PENDING", "DELIVERED").contains(state);
        jdbc.sql("""
                        insert into notification (id, app_id, user_id, external_id, policy, timeout_minutes,
                            fallback_payload, state, created_at, due_at, closed_at)
                        values (:id, :app, :user, :ext, 'NOT_DELIVERED', 5, cast(:payload as jsonb), :state,
                            :created, :due, :closed)
                        """)
                .param("id", id)
                .param("app", app)
                .param("user", user)
                .param("ext", externalId)
                .param("payload", PAYLOAD_JSON)
                .param("state", state)
                .param("created", Timestamp.from(createdAt))
                .param("due", closed ? null : Timestamp.from(NEVER_DUE))
                .param("closed", closed ? Timestamp.from(createdAt.plusSeconds(400)) : null)
                .update();
        return id;
    }

    private UUID device(String app, String user, String platform, Instant createdAt, boolean revoked) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at, revoked_at, token_expires_at)
                        values (:id, :app, :user, :platform, 1, :created, :seen, :revoked, now() + interval '90 days')
                        """)
                .param("id", id)
                .param("app", app)
                .param("user", user)
                .param("platform", platform)
                .param("created", Timestamp.from(createdAt))
                .param("seen", Timestamp.from(createdAt.plusSeconds(60)))
                .param("revoked", revoked ? Timestamp.from(createdAt.plusSeconds(120)) : null)
                .update();
        return id;
    }

    @Test
    void searchByUserNewestFirstWithCursor() {
        String user = user();
        UUID oldest = notification("shop", user, null, "PENDING", BASE);
        UUID middle = notification("shop", user, null, "PENDING", BASE.plusSeconds(10));
        UUID newest = notification("shop", user, null, "PENDING", BASE.plusSeconds(20));
        notification("other", user, null, "PENDING", BASE.plusSeconds(30));

        ResponseEntity<String> first = checked(ALL_KEY, "/v1/admin/notifications?app=shop&userId=" + enc(user) + "&limit=2");

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(JsonPath.<List<String>>read(first.getBody(), "$.items[*].trackingId"))
                .containsExactly(newest.toString(), middle.toString());
        assertThat(JsonPath.<String>read(first.getBody(), "$.items[0].userId")).isEqualTo(user);
        assertThat(JsonPath.<String>read(first.getBody(), "$.items[0].state")).isEqualTo("PENDING");
        assertThat(JsonPath.<Integer>read(first.getBody(), "$.items[0].fallbackAttempts")).isZero();
        String cursor = JsonPath.read(first.getBody(), "$.nextCursor");
        assertThat(cursor).isNotBlank();

        ResponseEntity<String> second =
                checked(ALL_KEY, "/v1/admin/notifications?app=shop&userId=" + enc(user) + "&limit=2&cursor=" + enc(cursor));

        assertThat(JsonPath.<List<String>>read(second.getBody(), "$.items[*].trackingId")).containsExactly(oldest.toString());
        assertThat(JsonPath.<Object>read(second.getBody(), "$.nextCursor")).isNull();
    }

    @Test
    void searchFilters() {
        String user = user();
        String ext = "ext-" + UUID.randomUUID();
        UUID early = notification("shop", user, ext, "PENDING", BASE);
        UUID late = notification("shop", user, null, "FALLBACK_FAILED", BASE.plus(2, ChronoUnit.HOURS));
        String base = "/v1/admin/notifications?app=shop&userId=" + enc(user);
        String otherUser = user();
        UUID untracked = notification("shop", otherUser, null, "NO_DEVICE_REGISTERED", BASE);
        notification("shop", otherUser, null, "CANCELLED", BASE);

        assertThat(ids(checked(SHOP_KEY, "/v1/admin/notifications?app=shop&userId=" + enc(otherUser)
                        + "&state=NO_DEVICE_REGISTERED")))
                .containsExactly(untracked.toString());

        assertThat(ids(checked(SHOP_KEY, base + "&externalId=" + enc(ext)))).containsExactly(early.toString());
        assertThat(ids(checked(SHOP_KEY, base + "&state=FALLBACK_FAILED"))).containsExactly(late.toString());
        assertThat(ids(checked(SHOP_KEY, base + "&from=2026-03-01T11:00:00Z"))).containsExactly(late.toString());
        assertThat(ids(checked(SHOP_KEY, base + "&to=2026-03-01T11:00:00Z"))).containsExactly(early.toString());
        // half-open [from, to): a row created exactly at `to` is excluded, at `from` included
        assertThat(ids(checked(SHOP_KEY, base + "&from=2026-03-01T10:00:00Z&to=2026-03-01T12:00:00Z")))
                .containsExactly(early.toString());

        for (String bad : List.of(
                "&state=nope", "&limit=0", "&limit=101", "&limit=x", "&from=2026-03-01T10:00:00Z&to=2026-03-01T10:00:00Z",
                "&from=2026-03-02T10:00:00Z&to=2026-03-01T10:00:00Z", "&from=yesterday", "&cursor=garbage")) {
            ResponseEntity<String> response = checked(SHOP_KEY, base + bad);
            assertThat(response.getStatusCode().value()).as(bad).isEqualTo(400);
        }
        assertThat(JsonPath.<List<String>>read(checked(SHOP_KEY, base + "&state=nope").getBody(), "$.errors[*].field"))
                .containsExactly("state");
    }

    @Test
    void oddUserIdIsFound() {
        String user = "a/b c<&>é" + UUID.randomUUID();
        UUID id = notification("shop", user, null, "PENDING", BASE);

        ResponseEntity<String> response = checked(SHOP_KEY, "/v1/admin/notifications?app=shop&userId=" + enc(user));

        assertThat(ids(response)).containsExactly(id.toString());
        assertThat(JsonPath.<String>read(response.getBody(), "$.items[0].userId")).isEqualTo(user);
    }

    @Test
    void appRequiredAndScoped() {
        String user = user();
        UUID otherId = notification("other", user, null, "PENDING", BASE);

        assertThat(checked(SHOP_KEY, "/v1/admin/notifications").getStatusCode().value()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(checked(SHOP_KEY, "/v1/admin/notifications").getBody(), "$.errors[*].field"))
                .containsExactly("app");
        ResponseEntity<String> hidden = checked(SHOP_KEY, "/v1/admin/notifications?app=other");
        assertThat(hidden.getStatusCode().value()).isEqualTo(404);
        ResponseEntity<String> unknownApp = checked(SHOP_KEY, "/v1/admin/notifications?app=nosuch");
        assertThat(sameProblem(unknownApp)).isEqualTo(sameProblem(hidden));

        ResponseEntity<String> detail = checked(SHOP_KEY, "/v1/admin/notifications/" + otherId);
        assertThat(detail.getStatusCode().value()).isEqualTo(404);
        ResponseEntity<String> unknown = checked(SHOP_KEY, "/v1/admin/notifications/" + UUID.randomUUID());
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(sameProblem(detail)).isEqualTo(sameProblem(unknown));
        assertThat(sameProblem(checked(SHOP_KEY, "/v1/admin/notifications/not-a-uuid"))).isEqualTo(sameProblem(unknown));
        assertThat(checked(SHOP_KEY, "/v1/admin/notifications/" + otherId + "/payload").getStatusCode().value()).isEqualTo(404);
        assertThat(checked(SHOP_KEY, "/v1/admin/devices?app=other&userId=" + enc(user)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void detailHasEventsAndAttempts() {
        String user = user();
        UUID ios = device("shop", user, "IOS", BASE, false);
        UUID id = notification("shop", user, "ext-" + UUID.randomUUID(), "FALLBACK_TRIGGERED", BASE);
        jdbc.sql("update notification set fallback_attempts = 2 where id = :id").param("id", id).update();
        jdbc.sql("""
                        insert into notification_event (notification_id, device_id, type, occurred_at, received_at, late)
                        values (:id, :device, 'DELIVERED', :at, :at, false)
                        """)
                .param("id", id)
                .param("device", ios)
                .param("at", Timestamp.from(BASE.plusSeconds(5)))
                .update();
        attempts.insert(new FallbackAttempt(id, 1, BASE.plusSeconds(300), 40, "RETRYABLE", 503, "http_503"));
        attempts.insert(new FallbackAttempt(id, 2, BASE.plusSeconds(310), 35, "SUCCESS", 200, null));

        ResponseEntity<String> response = checked(SHOP_KEY, "/v1/admin/notifications/" + id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.notification.trackingId")).isEqualTo(id.toString());
        assertThat(JsonPath.<String>read(body, "$.notification.state")).isEqualTo("FALLBACK_TRIGGERED");
        assertThat(JsonPath.<Integer>read(body, "$.notification.timeoutMinutes")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(body, "$.notification.fallbackAttempts")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(body, "$.events[*].type")).containsExactly("DELIVERED");
        assertThat(JsonPath.<String>read(body, "$.events[0].platform")).isEqualTo("IOS");
        assertThat(JsonPath.<String>read(body, "$.events[0].deviceId")).isEqualTo(ios.toString());
        assertThat(JsonPath.<Boolean>read(body, "$.events[0].late")).isFalse();
        assertThat(JsonPath.<List<Integer>>read(body, "$.attempts[*].attempt")).containsExactly(1, 2);
        assertThat(JsonPath.<List<String>>read(body, "$.attempts[*].outcome")).containsExactly("RETRYABLE", "SUCCESS");
        assertThat(JsonPath.<Integer>read(body, "$.attempts[0].httpStatus")).isEqualTo(503);
        assertThat(JsonPath.<String>read(body, "$.attempts[0].reason")).isEqualTo("http_503");
        assertThat(JsonPath.<Integer>read(body, "$.payloadBytes")).isPositive();
        assertThat(body).doesNotContain("\"payload\"").doesNotContain("123456");
    }

    @Test
    void payloadNeedsPermission() {
        UUID id = notification("shop", user(), null, "PENDING", BASE);
        String path = "/v1/admin/notifications/" + id + "/payload";

        ResponseEntity<String> denied = checked(SHOP_KEY, path);
        ResponseEntity<String> unknown = checked(SHOP_KEY, "/v1/admin/notifications/" + UUID.randomUUID() + "/payload");
        ResponseEntity<String> allowed = checked(ALL_KEY, path);

        assertThat(denied.getStatusCode().value()).isEqualTo(404);
        assertThat(sameProblem(denied)).isEqualTo(sameProblem(unknown));
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<String>read(allowed.getBody(), "$.sms")).isEqualTo("code 123456");
        assertThat(allowed.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(sameProblem(checked(ALL_KEY, "/v1/admin/notifications/" + UUID.randomUUID() + "/payload")))
                .isEqualTo(sameProblem(unknown));
    }

    @Test
    void devicesOfUser() {
        String user = user();
        UUID older = device("shop", user, "ANDROID", BASE, true);
        UUID newer = device("shop", user, "IOS", BASE.plusSeconds(100), false);
        device("shop", user(), "IOS", BASE.plusSeconds(200), false);

        ResponseEntity<String> response = checked(SHOP_KEY, "/v1/admin/devices?app=shop&userId=" + enc(user));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.items[*].deviceId"))
                .containsExactly(newer.toString(), older.toString());
        assertThat(JsonPath.<Object>read(response.getBody(), "$.items[0].revokedAt")).isNull();
        assertThat(JsonPath.<String>read(response.getBody(), "$.items[1].revokedAt")).isNotBlank();
        assertThat(JsonPath.<String>read(response.getBody(), "$.items[1].platform")).isEqualTo("ANDROID");
        assertThat(checked(SHOP_KEY, "/v1/admin/devices?app=shop").getStatusCode().value()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(checked(SHOP_KEY, "/v1/admin/devices?app=shop").getBody(), "$.errors[*].field"))
                .containsExactly("userId");
    }

    @Test
    void auditEvents(CapturedOutput output) {
        String user = "audit/user é " + UUID.randomUUID();
        UUID id = notification("shop", user, null, "PENDING", BASE);

        checked(ALL_KEY, "/v1/admin/notifications?app=shop&userId=" + enc(user) + "&state=PENDING");
        checked(ALL_KEY, "/v1/admin/notifications/" + id);
        checked(ALL_KEY, "/v1/admin/notifications/" + id + "/payload");
        checked(ALL_KEY, "/v1/admin/devices?app=shop&userId=" + enc(user));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<String> lines = output.getOut().lines().toList();
            String hash = UserHash.of(user);
            assertThat(lines).anySatisfy(l -> {
                assertThat(l).contains("\"admin.search\"");
                assertThat(JsonPath.<String>read(l, "$['rp.admin_key']")).isEqualTo("admin-all");
                assertThat(JsonPath.<String>read(l, "$['rp.app_id']")).isEqualTo("shop");
                assertThat(JsonPath.<String>read(l, "$.filters")).isEqualTo("state,userId");
            });
            assertThat(lines).anySatisfy(l -> {
                assertThat(l).contains("\"admin.devices_viewed\"");
                assertThat(JsonPath.<String>read(l, "$['rp.admin_key']")).isEqualTo("admin-all");
                assertThat(JsonPath.<String>read(l, "$['rp.app_id']")).isEqualTo("shop");
                assertThat(JsonPath.<String>read(l, "$['rp.user_hash']")).isEqualTo(hash);
                assertThat(JsonPath.<String>read(l, "$['log.level']")).isEqualTo("INFO");
            });
            for (String action : List.of("admin.notification_viewed", "admin.payload_viewed")) {
                assertThat(lines).as(action).anySatisfy(l -> {
                    assertThat(l).contains("\"" + action + "\"");
                    assertThat(JsonPath.<String>read(l, "$['rp.admin_key']")).isEqualTo("admin-all");
                    assertThat(JsonPath.<String>read(l, "$['rp.tracking_id']")).isEqualTo(id.toString());
                    assertThat(JsonPath.<String>read(l, "$['rp.user_hash']")).isEqualTo(hash);
                });
            }
        });
        assertThat(output.getAll()).doesNotContain(user).doesNotContain(enc(user)).doesNotContain("code 123456");
    }

    /** The problem body without its {@code instance} (the request's own path); everything else must be identical. */
    private static String sameProblem(ResponseEntity<String> response) {
        return response.getBody().replaceAll("\"instance\":\"[^\"]*\",?", "");
    }

    private static List<String> ids(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return JsonPath.read(response.getBody(), "$.items[*].trackingId");
    }
}
