package io.reliablepush.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.reliablepush.shared.logging.EventCapture;
import io.reliablepush.support.PostgresTestBase;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * The opt-in management port ({@code MANAGEMENT_SERVER_PORT}): probes and Prometheus run on their own port, outside the
 * public API filters (access log).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ManagementPortIT extends PostgresTestBase {

    private static final String SHOP_KEY = "test-sender-key-shop";

    @LocalManagementPort
    int managementPort;

    private RestClient management() {
        return RestClient.builder().baseUrl("http://localhost:" + managementPort).build();
    }

    private ResponseEntity<String> get(RestClient client, String path, String apiKey) {
        return client.get()
                .uri(path)
                .headers(h -> {
                    if (apiKey != null) {
                        h.set("X-Api-Key", apiKey);
                    }
                })
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    @Test
    void readinessOnManagementPortOnly() {
        assertThat(managementPort).isNotEqualTo(port);

        for (String path : new String[] {"/actuator/health/readiness", "/actuator/health/liveness"}) {
            assertThat(get(management(), path, null).getStatusCode().value()).as("management " + path).isEqualTo(200);
            assertThat(get(http, path, null).getStatusCode().value()).as("server " + path).isEqualTo(404);
        }
    }

    @Test
    void prometheusOnManagementPort() {
        ResponseEntity<String> onManagement = get(management(), "/actuator/prometheus", null);

        assertThat(onManagement.getStatusCode().value()).isEqualTo(200);
        assertThat(onManagement.getBody()).contains("rp_notifications_registered_total");
        assertThat(get(http, "/actuator/prometheus", null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void managementRequestsDoNotEmitApiRequestEvent() {
        try (EventCapture capture = new EventCapture()) {
            get(management(), "/actuator/health/readiness", null);
            get(management(), "/actuator/health/liveness", null);
            // control request on the API port: proves the capture works and that the access log has had time to run
            get(http, "/v1/notifications/" + UUID.randomUUID(), SHOP_KEY);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(apiRequestPaths(capture))
                    .anyMatch(path -> path.startsWith("/v1/notifications")));
            assertThat(apiRequestPaths(capture)).noneMatch(path -> path.startsWith("/actuator"));
        }
    }

    private static List<String> apiRequestPaths(EventCapture capture) {
        return capture.events().stream()
                .filter(e -> "api.request".equals(e.getMessage().getFormattedMessage()))
                .map(ManagementPortIT::urlPath)
                .toList();
    }

    private static String urlPath(LogEvent event) {
        return String.valueOf(EventCapture.data(event).get("url.path"));
    }

    @Test
    void apiStillServedOnServerPort() {
        String body = "{\"trackingId\":\"" + UUID.randomUUID() + "\",\"userId\":\"mgmt-user\",\"externalId\":\"mgmt-" + UUID.randomUUID()
                + "\",\"policy\":\"NOT_DELIVERED\",\"timeoutMinutes\":5,\"fallback\":{\"sms\":\"hi\"}}";

        ResponseEntity<String> response = http.post()
                .uri("/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Api-Key", SHOP_KEY)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, r) -> {})
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }
}
