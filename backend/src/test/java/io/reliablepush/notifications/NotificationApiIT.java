package io.reliablepush.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import io.reliablepush.support.TestDevices;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Sender API end to end against a real PostgreSQL. The database is shared with other ITs: every test uses fresh ids. */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class NotificationApiIT extends PostgresTestBase {

    private static final String SHOP_KEY = "test-sender-key-shop";
    private static final String OTHER_KEY = "test-sender-key-other";
    private static final String BASE = "/v1/notifications";

    @Autowired
    JdbcClient jdbc;

    // ---- helpers -----------------------------------------------------------------------------------------------

    private ResponseEntity<String> post(String apiKey, String body) {
        return http.post()
                .uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Api-Key", apiKey)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String apiKey, String uri) {
        return http.get()
                .uri(uri)
                .header("X-Api-Key", apiKey)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private ResponseEntity<String> delete(String apiKey, String uri) {
        return http.delete()
                .uri(uri)
                .header("X-Api-Key", apiKey)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    /** A fresh user with an active device, so that its notifications are tracked ({@code PENDING}). */
    private String uniqueUser() {
        String userId = "user-" + UUID.randomUUID();
        TestDevices.insertActive(jdbc, "shop", userId);
        TestDevices.insertActive(jdbc, "other", userId);
        return userId;
    }

    /** The body with a fresh {@code trackingId} as its first field (required by the sender API). */
    private static String tracked(String body) {
        return "{\"trackingId\":\"" + UUID.randomUUID() + "\"," + body.substring(1);
    }

    private static String registerBody(String userId) {
        return tracked("{\"userId\":\"" + userId + "\",\"fallback\":{\"sms\":\"Your order shipped\"}}");
    }

    private static String registerBody(String userId, String externalId) {
        return tracked("{\"userId\":\"" + userId + "\",\"externalId\":\"" + externalId + "\",\"fallback\":{\"sms\":\"hi\"}}");
    }

    private String register(String userId) {
        ResponseEntity<String> response = post(SHOP_KEY, registerBody(userId));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return JsonPath.read(response.getBody(), "$.trackingId");
    }

    private static void assertProblem(ResponseEntity<String> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.status")).isEqualTo(status);
    }

    private static List<String> errorFields(ResponseEntity<String> response) {
        return JsonPath.read(response.getBody(), "$.errors[*].field");
    }

    private static String fallbackOfSerializedSize(int bytes) {
        String prefix = "{\"k\":\"";
        String suffix = "\"}";
        return prefix + "x".repeat(bytes - prefix.length() - suffix.length()) + suffix;
    }

    // ---- register ----------------------------------------------------------------------------------------------

    @Test
    void registerReturns201WithTrackingIdAndDueAt() {
        String userId = uniqueUser();
        ResponseEntity<String> response = post(
                SHOP_KEY,
                tracked("{\"userId\":\"" + userId + "\",\"policy\":\"NOT_READ\",\"timeoutMinutes\":5,\"externalId\":\"ext-"
                        + UUID.randomUUID() + "\",\"fallback\":{\"sms\":\"secret fallback text\"}}"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String body = response.getBody();
        String trackingId = JsonPath.read(body, "$.trackingId");
        assertThat(UUID.fromString(trackingId)).isNotNull();
        assertThat(response.getHeaders().getFirst("Location")).isEqualTo(BASE + "/" + trackingId);
        assertThat(JsonPath.<String>read(body, "$.state")).isEqualTo("PENDING");
        assertThat(JsonPath.<String>read(body, "$.policy")).isEqualTo("NOT_READ");
        assertThat(JsonPath.<Integer>read(body, "$.timeoutMinutes")).isEqualTo(5);
        assertThat(JsonPath.<String>read(body, "$.userId")).isEqualTo(userId);
        Instant createdAt = Instant.parse(JsonPath.read(body, "$.createdAt"));
        assertThat(Instant.parse(JsonPath.<String>read(body, "$.dueAt"))).isEqualTo(createdAt.plus(Duration.ofMinutes(5)));
        assertThat(createdAt).isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(30));
        assertThat(JsonPath.<Object>read(body, "$.deliveredAt")).isNull();
        assertThat(JsonPath.<Object>read(body, "$.closedAt")).isNull();
        // the fallback content is write-only
        assertThat(body).doesNotContain("secret fallback text").doesNotContain("fallback");
    }

    @Test
    void registerAppliesAppDefaultsAndIgnoresUnknownFields() {
        ResponseEntity<String> response = post(
                SHOP_KEY, tracked("{\"userId\":\"" + uniqueUser() + "\",\"fallback\":{},\"somethingNew\":[1,2]}"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.getBody(), "$.policy")).isEqualTo("NOT_DELIVERED");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.timeoutMinutes")).isEqualTo(10);
    }

    @Test
    void repeatedRegistrationReturnsExisting(CapturedOutput output) {
        String externalId = "ext-" + UUID.randomUUID();
        String userId = uniqueUser();
        String body = registerBody(userId, externalId);

        ResponseEntity<String> first = post(SHOP_KEY, body);
        ResponseEntity<String> second = post(SHOP_KEY, body);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        String trackingId = JsonPath.read(first.getBody(), "$.trackingId");
        assertThat(JsonPath.<String>read(second.getBody(), "$.trackingId")).isEqualTo(trackingId);
        assertThat(JsonPath.<String>read(second.getBody(), "$.createdAt")).isEqualTo(JsonPath.<String>read(first.getBody(), "$.createdAt"));
        assertThat(jdbc.sql("select count(*) from notification where app_id = 'shop' and external_id = :e")
                        .param("e", externalId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(eventLines(output, "notification.duplicate"))
                .anySatisfy(line -> {
                    assertThat(JsonPath.<String>read(line, "$['rp.tracking_id']")).isEqualTo(trackingId);
                    assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo("shop");
                }));
    }

    @Test
    void sameExternalIdInOtherAppIsAnotherNotification() {
        String externalId = "ext-" + UUID.randomUUID();

        String shop = JsonPath.read(post(SHOP_KEY, registerBody(uniqueUser(), externalId)).getBody(), "$.trackingId");
        String other = JsonPath.read(post(OTHER_KEY, registerBody(uniqueUser(), externalId)).getBody(), "$.trackingId");

        assertThat(other).isNotEqualTo(shop);
    }

    @Test
    void concurrentRegistrationsWithSameExternalIdCreateOneRow() throws Exception {
        String externalId = "ext-race-" + UUID.randomUUID();
        String userId = uniqueUser();
        String body = registerBody(userId, externalId);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < threads; i++) {
                Callable<ResponseEntity<String>> call = () -> {
                    go.await();
                    return post(SHOP_KEY, body);
                };
                futures.add(pool.submit(call));
            }
            go.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get();
                assertThat(response.getStatusCode().value()).isEqualTo(201);
                ids.add(JsonPath.read(response.getBody(), "$.trackingId"));
            }
            assertThat(ids).hasSize(threads).containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("select count(*) from notification where app_id = 'shop' and external_id = :e")
                        .param("e", externalId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void registerValidation() {
        String ok = "\"fallback\":{\"a\":1}";
        record Case(String name, String body, String field) {}
        List<Case> cases = List.of(
                new Case("empty userId", "{\"userId\":\"\"," + ok + "}", "userId"),
                new Case("missing userId", "{" + ok + "}", "userId"),
                new Case("userId too long", "{\"userId\":\"" + "u".repeat(257) + "\"," + ok + "}", "userId"),
                new Case("empty externalId", "{\"userId\":\"u\",\"externalId\":\"\"," + ok + "}", "externalId"),
                new Case("externalId too long", "{\"userId\":\"u\",\"externalId\":\"" + "e".repeat(129) + "\"," + ok + "}", "externalId"),
                new Case("timeout 0", "{\"userId\":\"u\",\"timeoutMinutes\":0," + ok + "}", "timeoutMinutes"),
                new Case("timeout too big", "{\"userId\":\"u\",\"timeoutMinutes\":10081," + ok + "}", "timeoutMinutes"),
                new Case("missing fallback", "{\"userId\":\"u\"}", "fallback"),
                new Case("null fallback", "{\"userId\":\"u\",\"fallback\":null}", "fallback"),
                new Case("array fallback", "{\"userId\":\"u\",\"fallback\":[1]}", "fallback"),
                new Case("string fallback", "{\"userId\":\"u\",\"fallback\":\"sms\"}", "fallback"));

        for (Case c : cases) {
            ResponseEntity<String> response = post(SHOP_KEY, tracked(c.body()));
            assertProblem(response, 422);
            assertThat(errorFields(response)).as(c.name()).contains(c.field());
        }
        // limits are inclusive
        assertThat(post(SHOP_KEY, tracked("{\"userId\":\"" + "u".repeat(256) + "\",\"externalId\":\"" + "e".repeat(128)
                        + "\",\"timeoutMinutes\":10080," + ok + "}"))
                .getStatusCode()
                .value())
                .isEqualTo(201);
        assertThat(post(SHOP_KEY, tracked("{\"userId\":\"u\",\"timeoutMinutes\":1," + ok + "}")).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void unreadableBodyIs400() {
        assertProblem(post(SHOP_KEY, tracked("{\"userId\":\"u\",\"policy\":\"SOMETIMES\",\"fallback\":{}}")), 400);
        assertProblem(post(SHOP_KEY, "{not json"), 400);
    }

    @Test
    void fallbackOver4KbIs413or422() {
        // 4096 bytes serialized is allowed, one byte more is not
        ResponseEntity<String> atLimit = post(SHOP_KEY, tracked("{\"userId\":\"" + uniqueUser() + "\",\"fallback\":" + fallbackOfSerializedSize(4096) + "}"));
        assertThat(atLimit.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> over = post(SHOP_KEY, tracked("{\"userId\":\"" + uniqueUser() + "\",\"fallback\":" + fallbackOfSerializedSize(4097) + "}"));
        assertProblem(over, 422);
        assertThat(errorFields(over)).containsExactly("fallback");
        // the rejected content is not echoed
        assertThat(over.getBody()).doesNotContain("xxxx");
    }

    @Test
    void multiByteFallbackOver4KbIs422() {
        // 2100 characters but about 4200 UTF-8 bytes
        String fallback = "{\"k\":\"" + "\u00e9".repeat(2100) + "\"}";
        ResponseEntity<String> response = post(SHOP_KEY, tracked("{\"userId\":\"" + uniqueUser() + "\",\"fallback\":" + fallback + "}"));
        assertProblem(response, 422);
        assertThat(errorFields(response)).containsExactly("fallback");
    }

    // ---- get ---------------------------------------------------------------------------------------------------

    @Test
    void getShowsExternalState() {
        String trackingId = register(uniqueUser());

        assertThat(JsonPath.<String>read(get(SHOP_KEY, BASE + "/" + trackingId).getBody(), "$.state")).isEqualTo("PENDING");

        jdbc.sql("update notification set state = 'DISPATCHING' where id = :id").param("id", UUID.fromString(trackingId)).update();
        assertThat(JsonPath.<String>read(get(SHOP_KEY, BASE + "/" + trackingId).getBody(), "$.state")).isEqualTo("FALLBACK_PENDING");

        jdbc.sql("update notification set state = 'DELIVERED', delivered_at = now() where id = :id")
                .param("id", UUID.fromString(trackingId))
                .update();
        ResponseEntity<String> delivered = get(SHOP_KEY, BASE + "/" + trackingId);
        assertThat(JsonPath.<String>read(delivered.getBody(), "$.state")).isEqualTo("DELIVERED");
        assertThat(JsonPath.<String>read(delivered.getBody(), "$.deliveredAt")).isNotBlank();
    }

    @Test
    void otherAppCannotRead() {
        String trackingId = register(uniqueUser());

        assertProblem(get(OTHER_KEY, BASE + "/" + trackingId), 404);
        assertProblem(delete(OTHER_KEY, BASE + "/" + trackingId), 404);
        // untouched by the foreign attempts
        assertThat(JsonPath.<String>read(get(SHOP_KEY, BASE + "/" + trackingId).getBody(), "$.state")).isEqualTo("PENDING");
    }

    @Test
    void unknownAndMalformedIdsAre404() {
        assertProblem(get(SHOP_KEY, BASE + "/" + UUID.randomUUID()), 404);
        assertProblem(get(SHOP_KEY, BASE + "/not-a-uuid"), 404);
        assertProblem(delete(SHOP_KEY, BASE + "/" + UUID.randomUUID()), 404);
    }

    @Test
    void apiRequestLogsPathTemplateForGet(CapturedOutput output) {
        String trackingId = register(uniqueUser());

        get(SHOP_KEY, BASE + "/" + trackingId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(eventLines(output, "api.request"))
                .anySatisfy(line -> {
                    assertThat(JsonPath.<String>read(line, "$['http.request.method']")).isEqualTo("GET");
                    assertThat(JsonPath.<String>read(line, "$['url.path']")).isEqualTo("/v1/notifications/{trackingId}");
                    assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo("shop");
                }));
    }

    // ---- list --------------------------------------------------------------------------------------------------

    @Test
    void listNewestFirstWithCursor() {
        String userId = uniqueUser();
        String first = register(userId);
        String second = register(userId);
        String third = register(userId);
        register(uniqueUser()); // other user, not listed

        ResponseEntity<String> page1 = get(SHOP_KEY, BASE + "?userId=" + userId + "&limit=2");
        assertThat(page1.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(page1.getBody(), "$.items[*].trackingId")).containsExactly(third, second);
        String cursor = JsonPath.read(page1.getBody(), "$.nextCursor");
        assertThat(cursor).isNotBlank();

        ResponseEntity<String> page2 = get(SHOP_KEY, BASE + "?userId=" + userId + "&limit=2&cursor=" + cursor);
        assertThat(JsonPath.<List<String>>read(page2.getBody(), "$.items[*].trackingId")).containsExactly(first);
        assertThat(JsonPath.<Object>read(page2.getBody(), "$.nextCursor")).isNull();
        // the listing never carries the fallback content
        assertThat(page1.getBody()).doesNotContain("Your order shipped");
    }

    @Test
    void pagingWithIdenticalCreatedAtSeesEveryRowOnce() {
        String userId = uniqueUser();
        Instant sameInstant = Instant.parse("2026-01-01T00:00:00.123456Z");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id.toString());
            jdbc.sql("""
                            insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state, created_at, due_at)
                            values (:id, 'shop', :u, 'NOT_DELIVERED', 10, '{}'::jsonb, 'PENDING', :t, :t)
                            """)
                    .param("id", id)
                    .param("u", userId)
                    .param("t", sameInstant.atOffset(java.time.ZoneOffset.UTC))
                    .update();
        }
        List<String> expected = ids.stream().sorted(java.util.Comparator.reverseOrder()).toList();

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            ResponseEntity<String> response = get(SHOP_KEY, BASE + "?userId=" + userId + "&limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(ids(response));
            cursor = JsonPath.read(response.getBody(), "$.nextCursor");
            if (cursor == null) {
                break;
            }
        }
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    @Test
    void listIsScopedToTheApp() {
        String userId = uniqueUser();
        register(userId);

        ResponseEntity<String> other = get(OTHER_KEY, BASE + "?userId=" + userId);

        assertThat(other.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(other.getBody(), "$.items")).isEmpty();
    }

    @Test
    void listFiltersByStateAndSince() {
        String userId = uniqueUser();
        String keep = register(userId);
        String cancelled = register(userId);
        assertThat(delete(SHOP_KEY, BASE + "/" + cancelled).getStatusCode().value()).isEqualTo(204);

        assertThat(ids(get(SHOP_KEY, BASE + "?userId=" + userId + "&state=CANCELLED"))).containsExactly(cancelled);
        assertThat(ids(get(SHOP_KEY, BASE + "?userId=" + userId + "&state=PENDING"))).containsExactly(keep);
        assertThat(ids(get(SHOP_KEY, BASE + "?userId=" + userId + "&state=FALLBACK_PENDING"))).isEmpty();

        jdbc.sql("update notification set state = 'DISPATCHING' where id = :id").param("id", UUID.fromString(keep)).update();
        assertThat(ids(get(SHOP_KEY, BASE + "?userId=" + userId + "&state=FALLBACK_PENDING"))).containsExactly(keep);

        assertThat(ids(get(SHOP_KEY, BASE + "?userId=" + userId + "&since=" + Instant.now().minusSeconds(300))))
                .containsExactlyInAnyOrder(keep, cancelled);
        assertThat(ids(get(SHOP_KEY, BASE + "?userId=" + userId + "&since=" + Instant.now().plusSeconds(300)))).isEmpty();
    }

    private static List<String> ids(ResponseEntity<String> page) {
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        return JsonPath.read(page.getBody(), "$.items[*].trackingId");
    }

    @Test
    void limitAbove100Is422() {
        ResponseEntity<String> response = get(SHOP_KEY, BASE + "?limit=101");
        assertProblem(response, 422);
        assertThat(errorFields(response)).containsExactly("limit");

        assertProblem(get(SHOP_KEY, BASE + "?limit=0"), 422);
        assertProblem(get(SHOP_KEY, BASE + "?limit=abc"), 422);
        assertThat(get(SHOP_KEY, BASE + "?limit=100").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void invalidListParametersAre422() {
        ResponseEntity<String> garbage = get(SHOP_KEY, BASE + "?cursor=not-a-cursor!");
        assertProblem(garbage, 422);
        assertThat(errorFields(garbage)).containsExactly("cursor");

        // base64url of a well-formed text that is not "<instant>|<uuid>"
        assertProblem(get(SHOP_KEY, BASE + "?cursor=MjAyNnxub3QtYS11dWlk"), 422);

        ResponseEntity<String> state = get(SHOP_KEY, BASE + "?state=DISPATCHING");
        assertProblem(state, 422);
        assertThat(errorFields(state)).containsExactly("state");

        assertThat(errorFields(get(SHOP_KEY, BASE + "?since=yesterday"))).containsExactly("since");
        // well-formed but outside what PostgreSQL stores: 422, not a 500
        assertThat(errorFields(get(SHOP_KEY, BASE + "?since=%2B999999999-01-01T00:00:00Z"))).containsExactly("since");
        assertThat(errorFields(get(SHOP_KEY, BASE + "?since=1969-12-31T23:59:59Z"))).containsExactly("since");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("+999999999-01-01T00:00:00Z|" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(errorFields(get(SHOP_KEY, BASE + "?cursor=" + forged))).containsExactly("cursor");
        assertThat(errorFields(get(SHOP_KEY, BASE + "?userId=" + "u".repeat(257)))).containsExactly("userId");
    }

    // ---- cancel ------------------------------------------------------------------------------------------------

    @Test
    void cancelPendingIs204ThenCancelAgain409() {
        String trackingId = register(uniqueUser());

        ResponseEntity<String> cancelled = delete(SHOP_KEY, BASE + "/" + trackingId);
        assertThat(cancelled.getStatusCode().value()).isEqualTo(204);
        assertThat(cancelled.getBody()).isNull();

        ResponseEntity<String> view = get(SHOP_KEY, BASE + "/" + trackingId);
        assertThat(JsonPath.<String>read(view.getBody(), "$.state")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<String>read(view.getBody(), "$.closedAt")).isNotBlank();
        assertThat(JsonPath.<Object>read(view.getBody(), "$.dueAt")).isNull();
        assertThat(jdbc.sql("select due_at is null and closed_at is not null from notification where id = :id")
                        .param("id", UUID.fromString(trackingId))
                        .query(Boolean.class)
                        .single())
                .isTrue();

        assertProblem(delete(SHOP_KEY, BASE + "/" + trackingId), 409);
    }

    @Test
    void cancelDeliveredIs204ButClosedStatesAre409() {
        String delivered = register(uniqueUser());
        jdbc.sql("update notification set state = 'DELIVERED', delivered_at = now() where id = :id")
                .param("id", UUID.fromString(delivered))
                .update();
        assertThat(delete(SHOP_KEY, BASE + "/" + delivered).getStatusCode().value()).isEqualTo(204);

        for (String state : List.of("READ", "DISPATCHING", "FALLBACK_TRIGGERED", "FALLBACK_FAILED", "FALLBACK_EXPIRED")) {
            String id = register(uniqueUser());
            jdbc.sql("update notification set state = :s where id = :id")
                    .param("s", state)
                    .param("id", UUID.fromString(id))
                    .update();
            assertProblem(delete(SHOP_KEY, BASE + "/" + id), 409);
            assertThat(JsonPath.<String>read(get(SHOP_KEY, BASE + "/" + id).getBody(), "$.state"))
                    .isEqualTo(NotificationState.valueOf(state).external());
        }
    }

    @Test
    void cancelOfADeliveredRowClosedByItsPolicyIs409AndKeepsTheOutcome() {
        // NOT_DELIVERED satisfied by the delivery: DELIVERED and already closed (spec 4.1: 409 if already closed)
        String satisfied = register(uniqueUser());
        jdbc.sql("""
                        update notification set state = 'DELIVERED', delivered_at = now(), closed_at = now(), due_at = null
                         where id = :id and policy = 'NOT_DELIVERED'
                        """)
                .param("id", UUID.fromString(satisfied))
                .update();
        String closedAt = jdbc.sql("select closed_at::text from notification where id = :id")
                .param("id", UUID.fromString(satisfied))
                .query(String.class)
                .single();

        assertProblem(delete(SHOP_KEY, BASE + "/" + satisfied), 409);

        ResponseEntity<String> view = get(SHOP_KEY, BASE + "/" + satisfied);
        assertThat(JsonPath.<String>read(view.getBody(), "$.state")).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("select closed_at::text from notification where id = :id")
                        .param("id", UUID.fromString(satisfied))
                        .query(String.class)
                        .single())
                .isNotNull()
                .isEqualTo(closedAt);
    }

    @Test
    void cancelLogsEvent(CapturedOutput output) {
        String trackingId = register(uniqueUser());

        delete(SHOP_KEY, BASE + "/" + trackingId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(eventLines(output, "notification.cancelled"))
                .anySatisfy(line -> assertThat(JsonPath.<String>read(line, "$['rp.tracking_id']")).isEqualTo(trackingId)));
    }

    // ---- contract ----------------------------------------------------------------------------------------------

    @Test
    void responsesMatchContract() {
        String userId = uniqueUser();
        String registerBody = registerBody(userId, "ext-" + UUID.randomUUID());
        OpenApiAssert.assertRequestMatchesContract("POST", BASE, registerBody, SHOP_KEY, null);

        ResponseEntity<String> created = post(SHOP_KEY, registerBody);
        OpenApiAssert.assertMatchesContract("POST", BASE, created);
        String trackingId = JsonPath.read(created.getBody(), "$.trackingId");
        String path = BASE + "/" + trackingId;

        OpenApiAssert.assertMatchesContract("GET", path, get(SHOP_KEY, path));
        OpenApiAssert.assertMatchesContract("GET", BASE, get(SHOP_KEY, BASE + "?userId=" + userId + "&limit=1"));
        OpenApiAssert.assertMatchesContract("GET", path, get(OTHER_KEY, path));
        OpenApiAssert.assertMatchesContract("POST", BASE, post(SHOP_KEY, "{\"userId\":\"\"}"));
        OpenApiAssert.assertMatchesContract("POST", BASE, post(SHOP_KEY, "{broken"));
        OpenApiAssert.assertMatchesContract("GET", BASE, get(SHOP_KEY, BASE + "?limit=1000"));
        OpenApiAssert.assertMatchesContract("GET", BASE, get("not-a-key", BASE));
        OpenApiAssert.assertMatchesContract("DELETE", path, delete(SHOP_KEY, path));
        OpenApiAssert.assertMatchesContract("DELETE", path, delete(SHOP_KEY, path));
        OpenApiAssert.assertMatchesContract("GET", path, get(SHOP_KEY, path));
    }

    // ---- logging -----------------------------------------------------------------------------------------------

    @Test
    void registerLogsEventWithoutUserId(CapturedOutput output) {
        String userId = uniqueUser();
        String trackingId = JsonPath.read(post(SHOP_KEY, registerBody(userId, "ext-" + UUID.randomUUID())).getBody(), "$.trackingId");
        get(SHOP_KEY, BASE + "?userId=" + userId);
        get(SHOP_KEY, BASE + "/" + trackingId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(eventLines(output, "notification.registered")).anySatisfy(line -> {
                assertThat(JsonPath.<String>read(line, "$['rp.tracking_id']")).isEqualTo(trackingId);
                assertThat(JsonPath.<String>read(line, "$['rp.user_hash']")).isEqualTo(UserHash.of(userId));
                assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo("shop");
                assertThat(JsonPath.<String>read(line, "$['event.outcome']")).isEqualTo("success");
            });
            assertThat(eventLines(output, "api.request")).anySatisfy(line -> assertThat(line).contains("/v1/notifications"));
        });
        assertThat(output.getAll()).doesNotContain(userId).doesNotContain("Your order shipped");
    }

    private static List<String> eventLines(CapturedOutput output, String action) {
        return output.getOut().lines().filter(l -> l.contains("\"event.action\":\"" + action + "\"")).toList();
    }
}
