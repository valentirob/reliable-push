package io.reliablepush.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.devices.DeviceClaims;
import io.reliablepush.devices.DeviceTokenService;
import io.reliablepush.notifications.NotificationState;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import io.reliablepush.support.TestDevices;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code POST /v1/events} end to end against a real PostgreSQL. Notifications are registered through the sender API of
 * the apps {@code shop} and {@code other}; devices are rows plus tokens issued by {@link DeviceTokenService}. The
 * database is shared with other ITs: every test uses fresh user ids.
 */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class EventsApiIT extends PostgresTestBase {

    private static final String SHOP = "shop";
    private static final String SHOP_KEY = "test-sender-key-shop";
    private static final String OTHER = "other";
    private static final String OTHER_KEY = "test-sender-key-other";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DeviceTokenService tokens;

    private record Device(String appId, String userId, UUID id, String bearer) {}

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static String uniqueUser() {
        return "user-" + UUID.randomUUID();
    }

    private Device device(String appId, String userId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at, token_expires_at)
                values (?, ?, ?, 'ANDROID', 0, now(), now() - interval '1 hour', now() + interval '90 days')
                """).params(id, appId, userId).update();
        Instant now = Instant.now();
        String token = tokens.issue(new DeviceClaims(appId, userId, id, 0, now, now.plus(Duration.ofDays(1)))).token();
        return new Device(appId, userId, id, token);
    }

    /** Registers a tracked notification: the user gets an active device in the key's app first (no device: untracked). */
    private String register(String apiKey, String userId, FallbackPolicyName policy) {
        TestDevices.insertActive(jdbc, apiKey.equals(SHOP_KEY) ? SHOP : OTHER, userId);
        String body = "{\"trackingId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + userId + "\",\"policy\":\""
                + policy + "\",\"fallback\":{\"sms\":\"hi\"}}";
        ResponseEntity<String> response = http.post()
                .uri("/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Api-Key", apiKey)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, r) -> {})
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return JsonPath.read(response.getBody(), "$.trackingId");
    }

    private enum FallbackPolicyName { NOT_DELIVERED, NOT_READ }

    private static String event(String trackingId, String type) {
        return event(trackingId, type, Instant.now().toString());
    }

    private static String event(String trackingId, String type, String occurredAt) {
        return "{\"trackingId\":\"" + trackingId + "\",\"type\":\"" + type + "\",\"occurredAt\":\"" + occurredAt + "\"}";
    }

    private static String batch(String... events) {
        return "{\"events\":[" + String.join(",", events) + "]}";
    }

    private ResponseEntity<String> post(Device device, String body) {
        return http.post()
                .uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + device.bearer())
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, r) -> {})
                .toEntity(String.class);
    }

    /** Posts, checks 200 and the contract of request and response. */
    private ResponseEntity<String> postOk(Device device, String body) {
        OpenApiAssert.assertRequestMatchesContract("POST", "/v1/events", body, null, device.bearer());
        ResponseEntity<String> response = post(device, body);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        OpenApiAssert.assertMatchesContract("POST", "/v1/events", response);
        return response;
    }

    private static List<String> outcomes(ResponseEntity<String> response) {
        return JsonPath.read(response.getBody(), "$.items[*].outcome");
    }

    private String state(String trackingId) {
        return jdbc.sql("select state from notification where id = ?").param(UUID.fromString(trackingId))
                .query(String.class).single();
    }

    private List<Boolean> lateFlags(String trackingId) {
        return jdbc.sql("select late from notification_event where notification_id = ? order by id")
                .param(UUID.fromString(trackingId)).query(Boolean.class).list();
    }

    private static List<String> lines(CapturedOutput output, String action) {
        return output.getOut().lines().filter(l -> l.contains("\"event.action\":\"" + action + "\"")).toList();
    }

    // ---- tests -------------------------------------------------------------------------------------------------

    @Test
    void acceptsBatchAndUpdatesState() {
        String userId = uniqueUser();
        Device device = device(SHOP, userId);
        String delivered = register(SHOP_KEY, userId, FallbackPolicyName.NOT_DELIVERED);
        String read = register(SHOP_KEY, userId, FallbackPolicyName.NOT_READ);
        String deliveredOnly = register(SHOP_KEY, userId, FallbackPolicyName.NOT_READ);

        ResponseEntity<String> response = postOk(device, batch(
                event(delivered, "DELIVERED"), event(read, "READ"), event(deliveredOnly, "DELIVERED")));

        assertThat(JsonPath.<Integer>read(response.getBody(), "$.accepted")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.ignored")).isZero();
        assertThat(outcomes(response)).containsExactly("ACCEPTED", "ACCEPTED", "ACCEPTED");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.items[*].trackingId"))
                .containsExactly(delivered, read, deliveredOnly);
        assertThat(state(delivered)).isEqualTo("DELIVERED");
        assertThat(state(read)).isEqualTo("READ");
        assertThat(state(deliveredOnly)).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("select due_at is null from notification where id = ?").param(UUID.fromString(delivered))
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select due_at is null from notification where id = ?").param(UUID.fromString(deliveredOnly))
                .query(Boolean.class).single()).isFalse();
        assertThat(lateFlags(delivered)).containsExactly(false);
    }

    @Test
    void updatesLastSeenOncePerRequest() {
        Device device = device(SHOP, uniqueUser());
        String a = register(SHOP_KEY, device.userId(), FallbackPolicyName.NOT_READ);
        String b = register(SHOP_KEY, device.userId(), FallbackPolicyName.NOT_READ);

        postOk(device, batch(event(a, "DELIVERED"), event(b, "DELIVERED")));

        Instant lastSeen = jdbc.sql("select last_seen_at from device where id = ?").param(device.id())
                .query((rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant()).single();
        assertThat(lastSeen).isAfter(Instant.now().minus(Duration.ofMinutes(5)));
    }

    @Test
    void otherUsersTrackingIdForbiddenPerItem(CapturedOutput output) {
        String userId = uniqueUser();
        String otherUserId = uniqueUser();
        Device device = device(SHOP, userId);
        String mine = register(SHOP_KEY, userId, FallbackPolicyName.NOT_DELIVERED);
        String theirs = register(SHOP_KEY, otherUserId, FallbackPolicyName.NOT_DELIVERED);

        ResponseEntity<String> response = postOk(device, batch(event(theirs, "DELIVERED"), event(mine, "DELIVERED")));

        assertThat(outcomes(response)).containsExactly("FORBIDDEN", "ACCEPTED");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.accepted")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.ignored")).isEqualTo(1);
        assertThat(state(theirs)).isEqualTo("PENDING");
        assertThat(lateFlags(theirs)).isEmpty();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(lines(output, "authz.denied"))
                .anySatisfy(line -> {
                    assertThat(line).contains("\"log.level\":\"WARN\"").contains(theirs).contains(UserHash.of(userId));
                }));
        assertThat(output.getAll()).doesNotContain(userId).doesNotContain(otherUserId).doesNotContain(device.bearer());
    }

    @Test
    void notificationOfAnotherAppIsNotFound() {
        String userId = uniqueUser();
        Device device = device(SHOP, userId);
        String foreign = register(OTHER_KEY, userId, FallbackPolicyName.NOT_DELIVERED);

        ResponseEntity<String> response = postOk(device, batch(event(foreign, "DELIVERED")));

        assertThat(outcomes(response)).containsExactly("NOT_FOUND");
        assertThat(state(foreign)).isEqualTo("PENDING");
    }

    @Test
    void unknownTrackingIdNotFoundPerItem() {
        String userId = uniqueUser();
        Device device = device(SHOP, userId);
        String mine = register(SHOP_KEY, userId, FallbackPolicyName.NOT_DELIVERED);

        ResponseEntity<String> response = postOk(device, batch(
                event(UUID.randomUUID().toString(), "READ"), event(mine, "DELIVERED")));

        assertThat(outcomes(response)).containsExactly("NOT_FOUND", "ACCEPTED");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.accepted")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.ignored")).isEqualTo(1);
    }

    @Test
    void duplicateInSameBatchCountedOnce() {
        String userId = uniqueUser();
        Device device = device(SHOP, userId);
        String id = register(SHOP_KEY, userId, FallbackPolicyName.NOT_READ);

        ResponseEntity<String> response = postOk(device, batch(event(id, "DELIVERED"), event(id, "DELIVERED")));

        assertThat(outcomes(response)).containsExactly("ACCEPTED", "DUPLICATE");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.accepted")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.ignored")).isEqualTo(1);
        assertThat(lateFlags(id)).hasSize(1);
    }

    @Test
    void duplicateAcrossRequestsChangesNothing(CapturedOutput output) {
        String userId = uniqueUser();
        Device device = device(SHOP, userId);
        String id = register(SHOP_KEY, userId, FallbackPolicyName.NOT_READ);
        postOk(device, batch(event(id, "DELIVERED")));
        postOk(device, batch(event(id, "READ")));

        ResponseEntity<String> again = postOk(device, batch(event(id, "DELIVERED"), event(id, "READ")));

        assertThat(outcomes(again)).containsExactly("DUPLICATE", "DUPLICATE");
        assertThat(state(id)).isEqualTo("READ");
        assertThat(lateFlags(id)).containsExactly(false, false);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(lines(output, "tracking.event_ignored"))
                .anySatisfy(line -> assertThat(line).contains(id).contains("\"rp.event_type\":\"READ\"")));
    }

    @Test
    void eventForCancelledIsLate200() {
        String userId = uniqueUser();
        Device device = device(SHOP, userId);
        String id = register(SHOP_KEY, userId, FallbackPolicyName.NOT_READ);
        ResponseEntity<Void> cancelled = http.delete().uri("/v1/notifications/" + id).header("X-Api-Key", SHOP_KEY)
                .retrieve().toBodilessEntity();
        assertThat(cancelled.getStatusCode().value()).isEqualTo(204);

        ResponseEntity<String> response = postOk(device, batch(event(id, "DELIVERED")));

        assertThat(outcomes(response)).containsExactly("LATE");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.accepted")).isEqualTo(1);
        assertThat(state(id)).isEqualTo(NotificationState.CANCELLED.name());
        assertThat(lateFlags(id)).containsExactly(true);
    }

    @Test
    void eventAfterFallbackClaimedIsLate() {
        String userId = uniqueUser();
        Device device = device(SHOP, userId);
        String id = register(SHOP_KEY, userId, FallbackPolicyName.NOT_DELIVERED);
        // what the worker's claim does (task 12): DISPATCHING with a lease; due_at is kept
        jdbc.sql("update notification set state = 'DISPATCHING', next_attempt_at = now() + interval '2 minutes' where id = ?")
                .param(UUID.fromString(id)).update();

        ResponseEntity<String> response = postOk(device, batch(event(id, "READ")));

        assertThat(outcomes(response)).containsExactly("LATE");
        assertThat(state(id)).isEqualTo("DISPATCHING");
        assertThat(lateFlags(id)).containsExactly(true);
        assertThat(jdbc.sql("select read_at is null and delivered_at is null from notification where id = ?")
                .param(UUID.fromString(id)).query(Boolean.class).single()).isTrue();
    }

    @Test
    void batchOver50Is422() {
        Device device = device(SHOP, uniqueUser());
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            events.add(event(UUID.randomUUID().toString(), "DELIVERED"));
        }

        ResponseEntity<String> response = post(device, batch(events.toArray(String[]::new)));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        OpenApiAssert.assertMatchesContract("POST", "/v1/events", response);
    }

    @Test
    void batchOf50IsAccepted() {
        Device device = device(SHOP, uniqueUser());
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            events.add(event(UUID.randomUUID().toString(), "DELIVERED"));
        }

        ResponseEntity<String> response = postOk(device, batch(events.toArray(String[]::new)));

        assertThat(JsonPath.<Integer>read(response.getBody(), "$.ignored")).isEqualTo(50);
    }

    @Test
    void emptyBatchAndMissingFieldsAre422() {
        Device device = device(SHOP, uniqueUser());

        for (String body : List.of(
                "{\"events\":[]}",
                "{}",
                "{\"events\":[{\"type\":\"DELIVERED\",\"occurredAt\":\"2026-11-03T10:00:00Z\"}]}")) {
            ResponseEntity<String> response = post(device, body);
            assertThat(response.getStatusCode().value()).as(body).isEqualTo(422);
            OpenApiAssert.assertMatchesContract("POST", "/v1/events", response);
        }
    }

    @Test
    void unknownEventTypeAndMalformedBodyAre400() {
        Device device = device(SHOP, uniqueUser());

        ResponseEntity<String> unknownType = post(device, batch(event(UUID.randomUUID().toString(), "OPENED")));
        ResponseEntity<String> malformed = post(device, "{\"events\":");

        assertThat(unknownType.getStatusCode().value()).isEqualTo(400);
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void requiresDeviceToken() {
        ResponseEntity<String> response = http.post().uri("/v1/events").contentType(MediaType.APPLICATION_JSON)
                .body(batch(event(UUID.randomUUID().toString(), "DELIVERED")))
                .retrieve().onStatus(status -> true, (request, r) -> {}).toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void secondDeviceOfSameUserCounts() {
        String userId = uniqueUser();
        Device first = device(SHOP, userId);
        Device second = device(SHOP, userId);
        String id = register(SHOP_KEY, userId, FallbackPolicyName.NOT_READ);
        postOk(first, batch(event(id, "DELIVERED")));

        ResponseEntity<String> response = postOk(second, batch(event(id, "READ"), event(id, "DELIVERED")));

        assertThat(outcomes(response)).containsExactly("ACCEPTED", "DUPLICATE");
        assertThat(state(id)).isEqualTo("READ");
        assertThat(jdbc.sql("select device_id from notification_event where notification_id = ? and type = 'READ'")
                .param(UUID.fromString(id)).query(UUID.class).single()).isEqualTo(second.id());
    }

    @Test
    void occurredAtIsClampedNotUsedForState() {
        String userId = uniqueUser();
        Device device = device(SHOP, userId);
        String id = register(SHOP_KEY, userId, FallbackPolicyName.NOT_READ);

        postOk(device, batch(event(id, "READ", Instant.now().plus(Duration.ofDays(3)).toString())));

        assertThat(jdbc.sql("select occurred_at <= received_at from notification_event where notification_id = ?")
                .param(UUID.fromString(id)).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select read_at = (select received_at from notification_event where notification_id = ?)"
                        + " from notification where id = ?")
                .params(UUID.fromString(id), UUID.fromString(id)).query(Boolean.class).single()).isTrue();
    }

    @Test
    void logsTrackingEvents(CapturedOutput output) {
        String userId = uniqueUser();
        Device device = device(SHOP, userId);
        String ok = register(SHOP_KEY, userId, FallbackPolicyName.NOT_READ);
        String cancelled = register(SHOP_KEY, userId, FallbackPolicyName.NOT_READ);
        http.delete().uri("/v1/notifications/" + cancelled).header("X-Api-Key", SHOP_KEY).retrieve().toBodilessEntity();

        postOk(device, batch(event(ok, "DELIVERED"), event(cancelled, "DELIVERED")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<String> accepted = lines(output, "tracking.event_accepted");
            assertThat(accepted).anySatisfy(line -> assertThat(line)
                    .contains(ok).contains("\"rp.event_type\":\"DELIVERED\"").contains("\"rp.late\":false")
                    .contains("\"rp.device_id\":\"" + device.id() + "\"").contains(UserHash.of(userId)));
            assertThat(accepted).anySatisfy(line -> assertThat(line)
                    .contains(cancelled).contains("\"rp.event_type\":\"DELIVERED\"").contains("\"rp.late\":true"));
        });
        assertThat(output.getAll()).doesNotContain(userId).doesNotContain(device.bearer());
    }
}
