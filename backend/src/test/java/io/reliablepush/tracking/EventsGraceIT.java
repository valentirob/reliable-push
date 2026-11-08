package io.reliablepush.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.reliablepush.devices.DeviceClaims;
import io.reliablepush.devices.DeviceTokenService;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import io.reliablepush.support.TestDevices;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Grace period for expired device tokens (sidecar revision spec 2.4): {@code POST /v1/events} and {@code DELETE
 * /v1/devices/me} accept a token expired by less than the app's {@code device-token-events-grace} (default 90 days);
 * {@code token:refresh} does not, and revocation still applies. The app {@code shop} runs with the default grace.
 */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class EventsGraceIT extends PostgresTestBase {

    private static final String SHOP = "shop";
    private static final String SHOP_KEY = "test-sender-key-shop";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DeviceTokenService tokens;

    @Autowired
    MeterRegistry meters;

    private record Device(String userId, UUID id) {}

    private Device device() {
        String userId = "user-" + UUID.randomUUID();
        return new Device(userId, TestDevices.insertActive(jdbc, SHOP, userId));
    }

    /** A token of the device that expired {@code expiredAgo} ago. */
    private String tokenExpiredAgo(Device device, Duration expiredAgo) {
        Instant exp = Instant.now().minus(expiredAgo);
        return tokens.issue(new DeviceClaims(SHOP, device.userId(), device.id(), 0, exp.minus(Duration.ofDays(90)), exp)).token();
    }

    private String registerTracked(String userId) {
        String trackingId = UUID.randomUUID().toString();
        String body = "{\"trackingId\":\"" + trackingId + "\",\"userId\":\"" + userId
                + "\",\"policy\":\"NOT_DELIVERED\",\"fallback\":{\"sms\":\"hi\"}}";
        ResponseEntity<String> response = http.post().uri("/v1/notifications").contentType(MediaType.APPLICATION_JSON)
                .header("X-Api-Key", SHOP_KEY).body(body).retrieve().onStatus(s -> true, (q, r) -> {}).toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return trackingId;
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String bearer, String body) {
        var spec = http.method(method).uri(path).header("Authorization", "Bearer " + bearer);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> response = spec.retrieve().onStatus(s -> true, (q, r) -> {}).toEntity(String.class);
        OpenApiAssert.assertMatchesContract(method.name(), path, response);
        return response;
    }

    private ResponseEntity<String> postEvents(String bearer, String trackingId) {
        String body = "{\"events\":[{\"trackingId\":\"" + trackingId + "\",\"type\":\"DELIVERED\",\"occurredAt\":\""
                + Instant.now() + "\"}]}";
        OpenApiAssert.assertRequestMatchesContract("POST", "/v1/events", body, null, bearer);
        return call(HttpMethod.POST, "/v1/events", bearer, body);
    }

    private double eventCount(String token) {
        var counter = meters.find("rp.events").tag("type", "DELIVERED").tag("late", "false").tag("token", token).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void eventsWithAGraceTokenAreAcceptedAndCountedAsGrace(CapturedOutput output) {
        Device device = device();
        String trackingId = registerTracked(device.userId());
        double before = eventCount("grace");
        double validBefore = eventCount("valid");

        ResponseEntity<String> response = postEvents(tokenExpiredAgo(device, Duration.ofDays(10)), trackingId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.getBody(), "$.items[0].outcome")).isEqualTo("ACCEPTED");
        assertThat(eventCount("grace") - before).isEqualTo(1.0);
        assertThat(eventCount("valid") - validBefore).isZero();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut().lines()
                .filter(l -> l.contains("\"event.action\":\"tracking.event_accepted\"") && l.contains(trackingId)))
                .anySatisfy(l -> assertThat(l).contains("\"token_state\":\"grace\"")));
    }

    @Test
    void eventsWithAnUnexpiredTokenAreCountedAsValid(CapturedOutput output) {
        Device device = device();
        String trackingId = registerTracked(device.userId());
        double before = eventCount("valid");

        ResponseEntity<String> response = postEvents(tokenExpiredAgo(device, Duration.ofDays(-10)), trackingId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(eventCount("valid") - before).isEqualTo(1.0);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut().lines()
                .filter(l -> l.contains("\"event.action\":\"tracking.event_accepted\"") && l.contains(trackingId)))
                .anySatisfy(l -> assertThat(l).contains("\"token_state\":\"valid\"")));
    }

    @Test
    void logoutWithAGraceTokenIs204() {
        Device device = device();

        ResponseEntity<String> response = call(HttpMethod.DELETE, "/v1/devices/me", tokenExpiredAgo(device, Duration.ofDays(10)), null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.sql("select revoked_at is not null from device where id = ?").param(device.id())
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void tokenRefreshWithAGraceTokenIs401() {
        Device device = device();

        ResponseEntity<String> response = call(HttpMethod.POST, "/v1/devices/token:refresh", tokenExpiredAgo(device, Duration.ofDays(10)), null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void eventsPastTheGraceAre401() {
        Device device = device();
        String trackingId = registerTracked(device.userId());

        ResponseEntity<String> response = postEvents(tokenExpiredAgo(device, Duration.ofDays(91)), trackingId);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aRevokedDeviceIsRejectedInsideTheGrace() {
        Device device = device();
        String trackingId = registerTracked(device.userId());
        jdbc.sql("update device set revoked_at = now() where id = ?").param(device.id()).update();

        ResponseEntity<String> response = postEvents(tokenExpiredAgo(device, Duration.ofDays(10)), trackingId);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aGraceTokenNeverReachesTokenRefreshThroughADotSegmentPath() {
        Device device = device();
        String bearer = tokenExpiredAgo(device, Duration.ofDays(10));

        for (String path : new String[] {"/v1/events/../devices/token:refresh", "/v1/events/%2e%2e/devices/token:refresh",
            "/v1/events;x=1/../devices/token:refresh"}) {
            ResponseEntity<String> response = http.post().uri(path).header("Authorization", "Bearer " + bearer)
                    .retrieve().onStatus(s -> true, (q, r) -> {}).toEntity(String.class);
            assertThat(response.getStatusCode().value()).isIn(400, 401, 404);
            assertThat(response.getBody() == null ? "" : response.getBody()).doesNotContain("\"token\"");
        }
    }
}
