package io.reliablepush.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reliablepush.devices.DeviceClaims;
import io.reliablepush.devices.DeviceTokenService;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import io.reliablepush.support.TestDevices;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
 * {@code POST /v1/notifications} with the sender-supplied {@code trackingId} and the device check (sidecar revision
 * spec 2.1 to 2.3). The database is shared with other ITs: every test uses fresh ids and users.
 */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class NotificationRegistrationIT extends PostgresTestBase {

    private static final String SHOP = "shop";
    private static final String SHOP_KEY = "test-sender-key-shop";
    private static final String OTHER_KEY = "test-sender-key-other";
    private static final String BASE = "/v1/notifications";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    DeviceTokenService tokens;

    @Autowired
    DataSource dataSource;

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

    /** Posts and checks request and response against the contract. */
    private ResponseEntity<String> register(String apiKey, String body) {
        ResponseEntity<String> response = post(apiKey, body);
        OpenApiAssert.assertMatchesContract("POST", BASE, response);
        return response;
    }

    private ResponseEntity<String> request(String method, String apiKey, String uri) {
        ResponseEntity<String> response = http.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(uri)
                .header("X-Api-Key", apiKey)
                .retrieve()
                .onStatus(status -> true, (r, s) -> {})
                .toEntity(String.class);
        OpenApiAssert.assertMatchesContract(method, uri.replaceFirst("\\?.*", ""), response);
        return response;
    }

    private static String body(UUID trackingId, String userId) {
        return "{\"trackingId\":\"" + trackingId + "\",\"userId\":\"" + userId + "\",\"fallback\":{\"sms\":\"hi\"}}";
    }

    private static String body(UUID trackingId, String userId, String externalId) {
        return "{\"trackingId\":\"" + trackingId + "\",\"userId\":\"" + userId + "\",\"externalId\":\"" + externalId
                + "\",\"fallback\":{\"sms\":\"hi\"}}";
    }

    private static String uniqueUser() {
        return "user-" + UUID.randomUUID();
    }

    private String userWithDevice() {
        String userId = uniqueUser();
        TestDevices.insertActive(jdbc, SHOP, userId);
        return userId;
    }

    private long rows(UUID id) {
        return jdbc.sql("select count(*) from notification where id = :id").param("id", id).query(Long.class).single();
    }

    private String storedState(UUID id) {
        return jdbc.sql("select state from notification where id = :id").param("id", id).query(String.class).single();
    }

    private double registered(String outcome) {
        Counter counter = meters.find("rp.notifications.registered").tag("outcome", outcome).counter();
        assertThat(counter).as("rp_notifications_registered_total{outcome=%s}", outcome).isNotNull();
        return counter.count();
    }

    private long registrationTimings() {
        Timer timer = meters.find("rp.notification.registration").timer();
        assertThat(timer).as("rp_notification_registration_seconds").isNotNull();
        return timer.count();
    }

    private static List<String> eventLines(CapturedOutput output, String action) {
        return output.getOut().lines().filter(l -> l.contains("\"event.action\":\"" + action + "\"")).toList();
    }

    private static List<String> errorFields(ResponseEntity<String> response) {
        return JsonPath.read(response.getBody(), "$.errors[*].field");
    }

    // ---- tracking id -------------------------------------------------------------------------------------------

    @Test
    void registersWithGivenTrackingId() {
        UUID trackingId = UUID.randomUUID();
        double pendingBefore = registered("pending");
        long timedBefore = registrationTimings();

        ResponseEntity<String> response = register(SHOP_KEY, body(trackingId, userWithDevice()));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.getBody(), "$.trackingId")).isEqualTo(trackingId.toString());
        assertThat(JsonPath.<String>read(response.getBody(), "$.state")).isEqualTo("PENDING");
        assertThat(JsonPath.<String>read(response.getBody(), "$.dueAt")).isNotNull();
        assertThat(response.getHeaders().getFirst("Location")).isEqualTo(BASE + "/" + trackingId);
        assertThat(storedState(trackingId)).isEqualTo("PENDING");
        assertThat(registered("pending") - pendingBefore).isEqualTo(1.0);
        assertThat(registrationTimings() - timedBefore).isEqualTo(1);
    }

    @Test
    void sameTrackingIdSameAppIsIdempotent(CapturedOutput output) throws Exception {
        UUID trackingId = UUID.randomUUID();
        String userId = userWithDevice();
        ResponseEntity<String> first = register(SHOP_KEY, body(trackingId, userId));
        // other fields are not compared: the first registration wins
        ResponseEntity<String> second = register(SHOP_KEY, "{\"trackingId\":\"" + trackingId + "\",\"userId\":\"" + userId
                + "\",\"policy\":\"NOT_READ\",\"timeoutMinutes\":99,\"fallback\":{\"sms\":\"other\"}}");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(rows(trackingId)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(eventLines(output, "notification.duplicate"))
                .anySatisfy(line -> assertThat(JsonPath.<String>read(line, "$['rp.tracking_id']"))
                        .isEqualTo(trackingId.toString())));

        // a retry racing the first request: one row, every answer 201 with the same state
        UUID raced = UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<ResponseEntity<String>> call = () -> {
                    go.await();
                    return post(SHOP_KEY, body(raced, userId, "ext-" + raced));
                };
                futures.add(pool.submit(call));
            }
            go.countDown();
            List<String> bodies = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get();
                assertThat(response.getStatusCode().value()).isEqualTo(201);
                OpenApiAssert.assertMatchesContract("POST", BASE, response);
                bodies.add(response.getBody());
            }
            assertThat(bodies).containsOnly(bodies.get(0));
            assertThat(JsonPath.<String>read(bodies.get(0), "$.state")).isEqualTo("PENDING");
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows(raced)).isEqualTo(1);
    }

    @Test
    void sameTrackingIdOtherAppIs409() {
        UUID trackingId = UUID.randomUUID();
        assertThat(register(SHOP_KEY, body(trackingId, userWithDevice())).getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> other = register(OTHER_KEY, body(trackingId, uniqueUser()));

        assertThat(other.getStatusCode().value()).isEqualTo(409);
        assertThat(jdbc.sql("select app_id from notification where id = :id").param("id", trackingId)
                        .query(String.class).single())
                .isEqualTo(SHOP);
    }

    @Test
    void trackingIdExternalIdMismatchIs409() {
        String userId = userWithDevice();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String extA = "ext-" + a;
        String extB = "ext-" + b;
        assertThat(register(SHOP_KEY, body(a, userId, extA)).getStatusCode().value()).isEqualTo(201);
        assertThat(register(SHOP_KEY, body(b, userId, extB)).getStatusCode().value()).isEqualTo(201);

        // the tracking id of one row, the external id of another
        assertThat(register(SHOP_KEY, body(a, userId, extB)).getStatusCode().value()).isEqualTo(409);
        // a new tracking id with an external id already taken by another tracking id
        UUID fresh = UUID.randomUUID();
        assertThat(register(SHOP_KEY, body(fresh, userId, extA)).getStatusCode().value()).isEqualTo(409);
        assertThat(rows(fresh)).isZero();
        // the same pair again is a replay
        assertThat(register(SHOP_KEY, body(a, userId, extA)).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void missingTrackingIdIs400() {
        String userId = userWithDevice();
        List<String> bodies = List.of(
                "{\"userId\":\"" + userId + "\",\"fallback\":{}}",
                "{\"trackingId\":null,\"userId\":\"" + userId + "\",\"fallback\":{}}",
                "{\"trackingId\":\"\",\"userId\":\"" + userId + "\",\"fallback\":{}}",
                "{\"trackingId\":\"not-a-uuid\",\"userId\":\"" + userId + "\",\"fallback\":{}}",
                "{\"trackingId\":\"1-2-3-4-5\",\"userId\":\"" + userId + "\",\"fallback\":{}}");
        for (String body : bodies) {
            ResponseEntity<String> response = register(SHOP_KEY, body);
            assertThat(response.getStatusCode().value()).as(body).isEqualTo(400);
            assertThat(errorFields(response)).as(body).containsExactly("trackingId");
            assertThat(response.getBody()).doesNotContain("not-a-uuid");
        }
        assertThat(jdbc.sql("select count(*) from notification where user_id = :u").param("u", userId)
                        .query(Long.class).single())
                .isZero();
    }

    // ---- no device ---------------------------------------------------------------------------------------------

    @Test
    void noActiveDeviceStoresNoDeviceRegistered(CapturedOutput output) throws Exception {
        String userId = uniqueUser();
        UUID trackingId = UUID.randomUUID();
        double noDeviceBefore = registered("no_device_registered");
        double pendingBefore = registered("pending");

        ResponseEntity<String> response = register(SHOP_KEY, body(trackingId, userId));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String view = response.getBody();
        assertThat(JsonPath.<String>read(view, "$.state")).isEqualTo("NO_DEVICE_REGISTERED");
        assertThat(JsonPath.<Object>read(view, "$.dueAt")).isNull();
        assertThat(JsonPath.<String>read(view, "$.closedAt")).isEqualTo(JsonPath.<String>read(view, "$.createdAt"));
        assertThat(jdbc.sql("select due_at is null and next_attempt_at is null and closed_at = created_at"
                        + " from notification where id = :id").param("id", trackingId).query(Boolean.class).single())
                .isTrue();
        assertThat(registered("no_device_registered") - noDeviceBefore).isEqualTo(1.0);
        assertThat(registered("pending") - pendingBefore).isZero();

        // never claimable by the worker, whatever the time: the claim predicate cannot match it
        assertThat(jdbc.sql("""
                        select count(*) from notification
                         where id = :id
                           and ((state in ('PENDING', 'DELIVERED') and due_at <= :far)
                                or (state = 'DISPATCHING' and next_attempt_at <= :far))
                        """)
                        .param("id", trackingId)
                        .param("far", Instant.parse("9999-01-01T00:00:00Z").atOffset(ZoneOffset.UTC))
                        .query(Long.class).single())
                .isZero();
        Thread.sleep(600); // three worker polls of the test profile
        assertThat(storedState(trackingId)).isEqualTo("NO_DEVICE_REGISTERED");
        assertThat(jdbc.sql("select count(*) from fallback_attempt where notification_id = :id").param("id", trackingId)
                        .query(Long.class).single())
                .isZero();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(eventLines(output, "notification.no_device_registered"))
                .anySatisfy(line -> {
                    assertThat(line).contains("\"log.level\":\"INFO\"");
                    assertThat(JsonPath.<String>read(line, "$['rp.tracking_id']")).isEqualTo(trackingId.toString());
                    assertThat(JsonPath.<String>read(line, "$['rp.user_hash']")).isEqualTo(UserHash.of(userId));
                    assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo(SHOP);
                }));
        assertThat(output.getAll()).doesNotContain(userId);
        assertThat(eventLines(output, "notification.registered")).noneMatch(line -> line.contains(trackingId.toString()));

        // the listing filters by the new state
        ResponseEntity<String> listed = request("GET", SHOP_KEY, BASE + "?userId=" + userId + "&state=NO_DEVICE_REGISTERED");
        assertThat(JsonPath.<List<String>>read(listed.getBody(), "$.items[*].trackingId")).containsExactly(trackingId.toString());
    }

    @Test
    void revokedOnlyDeviceMeansNoDevice() {
        String userId = uniqueUser();
        TestDevices.insert(jdbc, SHOP, userId, Instant.now().plus(Duration.ofDays(30)), Instant.now().minusSeconds(60));

        ResponseEntity<String> response = register(SHOP_KEY, body(UUID.randomUUID(), userId));

        assertThat(JsonPath.<String>read(response.getBody(), "$.state")).isEqualTo("NO_DEVICE_REGISTERED");
    }

    @Test
    void expiredBeyondGraceMeansNoDevice() {
        // default grace: 90 days after the token's expiry
        String userId = uniqueUser();
        // past exp + 60 s verifier skew + grace (the active window equals token acceptance)
        TestDevices.insert(jdbc, SHOP, userId, Instant.now().minus(Duration.ofDays(90)).minusSeconds(120), null);
        // a device of the same user in another app does not count
        TestDevices.insertActive(jdbc, "other", userId);

        ResponseEntity<String> response = register(SHOP_KEY, body(UUID.randomUUID(), userId));

        assertThat(JsonPath.<String>read(response.getBody(), "$.state")).isEqualTo("NO_DEVICE_REGISTERED");
    }

    @Test
    void deviceInsideGraceCountsActive() {
        String userId = uniqueUser();
        TestDevices.insert(jdbc, SHOP, userId, Instant.now().minus(Duration.ofDays(89)), null);

        ResponseEntity<String> response = register(SHOP_KEY, body(UUID.randomUUID(), userId));

        assertThat(JsonPath.<String>read(response.getBody(), "$.state")).isEqualTo("PENDING");
    }

    @Test
    void cancelOfNoDeviceRowIsUnchanged() {
        UUID trackingId = UUID.randomUUID();
        String created = register(SHOP_KEY, body(trackingId, uniqueUser())).getBody();
        String path = BASE + "/" + trackingId;

        ResponseEntity<String> cancel = request("DELETE", SHOP_KEY, path);

        // final like CANCELLED: 409, nothing changes
        assertThat(cancel.getStatusCode().value()).isEqualTo(409);
        assertThat(request("GET", SHOP_KEY, path).getBody()).isEqualTo(created);
    }

    @Test
    void eventForNoDeviceRowIsLate() {
        String userId = uniqueUser();
        UUID trackingId = UUID.randomUUID();
        String created = register(SHOP_KEY, body(trackingId, userId)).getBody();

        // a device registered later does not reopen the row, not even through a replay of the registration
        UUID deviceId = TestDevices.insertActive(jdbc, SHOP, userId);
        assertThat(register(SHOP_KEY, body(trackingId, userId)).getBody()).isEqualTo(created);

        Instant now = Instant.now();
        String bearer = tokens.issue(new DeviceClaims(SHOP, userId, deviceId, 0, now, now.plus(Duration.ofDays(1)))).token();
        String events = "{\"events\":[{\"trackingId\":\"" + trackingId + "\",\"type\":\"DELIVERED\",\"occurredAt\":\""
                + now + "\"},{\"trackingId\":\"" + trackingId + "\",\"type\":\"READ\",\"occurredAt\":\"" + now + "\"}]}";
        ResponseEntity<String> response = http.post()
                .uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + bearer)
                .body(events)
                .retrieve()
                .onStatus(status -> true, (r, s) -> {})
                .toEntity(String.class);
        OpenApiAssert.assertMatchesContract("POST", "/v1/events", response);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.items[*].outcome")).containsExactly("LATE", "LATE");
        assertThat(jdbc.sql("select count(*) from notification_event where notification_id = :id and late")
                        .param("id", trackingId).query(Long.class).single())
                .isEqualTo(2);
        assertThat(request("GET", SHOP_KEY, BASE + "/" + trackingId).getBody()).isEqualTo(created);
    }

    // ---- migration ---------------------------------------------------------------------------------------------

    /** Flyway on a scratch schema, with the application's session-level lock (V5 builds its index concurrently). */
    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(dataSource)
                .schemas(schema)
                .createSchemas(true)
                .target(target)
                .load();
    }

    @Test
    void migrationBackfillsTokenExpiresAt() {
        String schema = "v7_backfill_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, "6").migrate();
            JdbcClient scoped = JdbcClient.create(dataSource);
            UUID device = UUID.randomUUID();
            scoped.sql("insert into " + schema + ".device (id, app_id, user_id, platform, token_version, created_at,"
                            + " last_seen_at) values (:id, 'shop', 'u', 'IOS', 0, :seen, :seen)")
                    .param("id", device)
                    .param("seen", Instant.parse("2026-10-01T12:00:00Z").atOffset(ZoneOffset.UTC))
                    .update();

            flyway(schema, "7").migrate();

            assertThat(scoped.sql("select token_expires_at from " + schema + ".device where id = :id")
                            .param("id", device).query(java.time.OffsetDateTime.class).single().toInstant())
                    .isEqualTo(Instant.parse("2036-09-28T12:00:00Z")); // last_seen_at + 87600 h: errs toward tracking
            assertThat(scoped.sql("select is_nullable from information_schema.columns"
                            + " where table_schema = :s and table_name = 'device' and column_name = 'token_expires_at'")
                            .param("s", schema).query(String.class).single())
                    .isEqualTo("NO");
            // the state check accepts the new state
            scoped.sql("insert into " + schema + ".notification (id, app_id, user_id, policy, timeout_minutes,"
                            + " fallback_payload, state, created_at, closed_at)"
                            + " values (:id, 'shop', 'u', 'NOT_DELIVERED', 10, '{}'::jsonb, 'NO_DEVICE_REGISTERED', now(), now())")
                    .param("id", UUID.randomUUID())
                    .update();
        } finally {
            JdbcClient.create(dataSource).sql("drop schema if exists " + schema + " cascade").update();
        }
    }
}
