package io.reliablepush.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.PostgresTestBase;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SenderApiKeyIT extends PostgresTestBase {

    private static final String SHOP_KEY = "test-sender-key-shop";
    private static final String LIMITED_KEY = "test-sender-key-limited";
    private static final String ECHO = "/v1/notifications/_test-echo";

    private ResponseEntity<String> get(String path, String apiKey) {
        return http.get()
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

    private ResponseEntity<String> postJson(String path, String apiKey, String body) {
        return http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Api-Key", apiKey)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private static String jsonOfSize(int bytes) {
        String prefix = "{\"pad\":\"";
        String suffix = "\"}";
        return prefix + "x".repeat(bytes - prefix.length() - suffix.length()) + suffix;
    }

    private static void assertProblem(ResponseEntity<String> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.status")).isEqualTo(status);
        assertThat(JsonPath.<String>read(response.getBody(), "$.title")).isNotBlank();
    }

    @Test
    void missingKey401(CapturedOutput output) {
        ResponseEntity<String> response = get("/v1/notifications/" + UUID.randomUUID(), null);

        assertProblem(response, 401);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(authFailedLines(output))
                .anySatisfy(line -> assertThat(JsonPath.<String>read(line, "$.reason")).isEqualTo("missing_key")));
    }

    @Test
    void unknownKey401(CapturedOutput output) {
        String wrongKey = "wrong-key-" + UUID.randomUUID();

        ResponseEntity<String> response = get("/v1/notifications/" + UUID.randomUUID(), wrongKey);

        assertProblem(response, 401);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(authFailedLines(output))
                .anySatisfy(line -> assertThat(JsonPath.<String>read(line, "$.reason")).isEqualTo("unknown_key")));
        assertThat(output.getAll()).doesNotContain(wrongKey);
    }

    @Test
    void unauthorizedBodiesDoNotRevealWhetherAKeyExists() {
        ResponseEntity<String> missing = get("/v1/notifications/" + UUID.randomUUID(), null);
        ResponseEntity<String> unknown = get("/v1/notifications/" + UUID.randomUUID(), "wrong-" + UUID.randomUUID());

        assertThat(JsonPath.<String>read(unknown.getBody(), "$.detail")).isEqualTo(JsonPath.read(missing.getBody(), "$.detail"));
        assertThat(JsonPath.<String>read(unknown.getBody(), "$.title")).isEqualTo(JsonPath.read(missing.getBody(), "$.title"));
    }

    @Test
    void protectsEveryPathUnderNotifications() {
        assertProblem(get("/v1/notifications", null), 401);
        assertProblem(get("/v1/notifications/", null), 401);
        assertProblem(get("/v1/notifications;x=1", null), 401);
        // percent-encoded spelling: Tomcat decodes it for the servlet path, so the filter must still apply
        ResponseEntity<String> encoded = http.get()
                .uri(URI.create("http://localhost:" + port + "/v1/%6Eotifications/" + UUID.randomUUID()))
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
        assertProblem(encoded, 401);
        assertProblem(postJson(ECHO, "wrong", "{}"), 401);
    }

    @Test
    void validKeyPasses() {
        ResponseEntity<String> response = get("/v1/notifications/" + UUID.randomUUID(), SHOP_KEY);

        // no notifications controller yet: Spring's no-handler path must still answer with a problem document
        assertProblem(response, 404);
    }

    @Test
    void validKeyPassesToHandlersAndSetsTheAppForTheAccessLog(CapturedOutput output) {
        ResponseEntity<String> response = postJson(ECHO, SHOP_KEY, "{\"a\":1}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut().lines())
                .anySatisfy(line -> {
                    assertThat(line).contains("\"api.request\"").contains(ECHO);
                    assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo("shop");
                }));
    }

    @Test
    void nonSenderPathsNeedNoKey() {
        assertThat(http.get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void rateLimited429(CapturedOutput output) {
        String path = "/v1/notifications/" + UUID.randomUUID();

        assertThat(get(path, LIMITED_KEY).getStatusCode().value()).isEqualTo(404);
        assertThat(get(path, LIMITED_KEY).getStatusCode().value()).isEqualTo(404);
        ResponseEntity<String> third = get(path, LIMITED_KEY);

        assertProblem(third, 429);
        long retryAfter = Long.parseLong(third.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 60L);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut().lines())
                .anySatisfy(line -> {
                    assertThat(line).contains("\"rate_limit.exceeded\"");
                    assertThat(JsonPath.<String>read(line, "$['rp.app_id']")).isEqualTo("ratelimited");
                    assertThat(JsonPath.<String>read(line, "$['log.level']")).isEqualTo("WARN");
                }));
        // other apps keep their own bucket
        assertThat(get(path, SHOP_KEY).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void bodyOver64KbIs413() {
        ResponseEntity<String> response = postJson(ECHO, SHOP_KEY, jsonOfSize(65 * 1024));

        assertProblem(response, 413);
    }

    @Test
    void bodyUpTo64KbIsAccepted() {
        ResponseEntity<String> response = postJson(ECHO, SHOP_KEY, jsonOfSize(64 * 1024));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void chunkedBodyOver64KbIs413() throws IOException {
        int[] result = postChunked(jsonOfSize(65 * 1024));

        assertThat(result[0]).isEqualTo(413);
    }

    @Test
    void chunkedBodyUpTo64KbIsAccepted() throws IOException {
        int[] result = postChunked(jsonOfSize(1024));

        assertThat(result[0]).isEqualTo(200);
    }

    @Test
    void bodyLimitAlsoAppliesToOtherV1Paths() {
        // not under /v1/notifications, so no key needed; the size check runs first and answers 413 before routing
        ResponseEntity<String> response = http.post()
                .uri("/v1/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonOfSize(65 * 1024))
                .retrieve()
                .onStatus(status -> true, (request, resp) -> {})
                .toEntity(String.class);

        assertProblem(response, 413);
    }

    @Test
    void chunkedMultipartBodyIsNeverParsedOrSpooled() throws IOException {
        String boundary = "rpTestBoundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n" + "x".repeat(100 * 1024) + "\r\n--" + boundary + "--\r\n";
        String type = "multipart/form-data; boundary=" + boundary;

        // unauthenticated, unmapped-for-multipart path under /v1
        int open = postChunked("/v1/devices", type, body, null)[0];
        // handler that would answer 200 if the container had parsed the multipart body
        int echo = postChunked(ECHO + "/multipart", type, body, SHOP_KEY)[0];

        assertThat(open).isBetween(400, 499);
        assertThat(echo).isBetween(400, 499);
    }

    @Test
    void chunkedFormBodyOver64KbIsNotParsed() throws IOException {
        String body = "a=" + "x".repeat(100 * 1024);

        int status = postChunked(ECHO + "/form", "application/x-www-form-urlencoded", body, SHOP_KEY)[0];

        assertThat(status).isBetween(400, 499);
    }

    private int[] postChunked(String body) throws IOException {
        return postChunked(ECHO, "application/json", body, SHOP_KEY);
    }

    /** Sends the body with {@code Transfer-Encoding: chunked} (no Content-Length) and returns {status}. */
    private int[] postChunked(String path, String contentType, String body, String apiKey) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create("http://localhost:" + port + path).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(4096);
        connection.setRequestProperty("Content-Type", contentType);
        if (apiKey != null) {
            connection.setRequestProperty("X-Api-Key", apiKey);
        }
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // the server may answer 413 and close while we are still writing; the status is read below
        }
        try {
            return new int[] {connection.getResponseCode()};
        } finally {
            connection.disconnect();
        }
    }

    private static java.util.List<String> authFailedLines(CapturedOutput output) {
        return output.getOut().lines().filter(l -> l.contains("\"auth.failed\"")).toList();
    }
}
