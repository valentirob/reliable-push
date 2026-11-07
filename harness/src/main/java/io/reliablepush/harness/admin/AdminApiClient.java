package io.reliablepush.harness.admin;

import io.reliablepush.harness.config.HarnessProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only client of the backend's admin API ({@code /v1/admin/*}, header {@code X-Admin-Key}); used by the
 * {@code admin-dashboard} scenario. The key comes from {@code HARNESS_ADMINKEY} and is never put in a message.
 */
@Component
public class AdminApiClient {

    static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI baseUrl;
    private final String adminKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Autowired
    public AdminApiClient(HarnessProperties properties) {
        this(properties.backendUrl(), properties.adminKey());
    }

    public AdminApiClient(URI baseUrl, String adminKey) {
        this.baseUrl = baseUrl;
        this.adminKey = adminKey;
    }

    /** {@code GET /v1/admin/overview?app=&window=}: {@code {items: [...]}}. */
    public JsonNode overview(String app, String window) {
        Response r = get("/v1/admin/overview?app=" + enc(app) + "&window=" + enc(window));
        return r.requireOk();
    }

    /** {@code GET /v1/admin/notifications?app=&userId=}: {@code {items: [...], nextCursor}}. */
    public JsonNode search(String app, String userId) {
        return get("/v1/admin/notifications?app=" + enc(app) + "&userId=" + enc(userId)).requireOk();
    }

    /** {@code GET /v1/admin/notifications/{trackingId}}; empty when the backend answers 404. */
    public Optional<JsonNode> detail(UUID trackingId) {
        Response r = get("/v1/admin/notifications/" + trackingId);
        if (r.status == 404) {
            return Optional.empty();
        }
        return Optional.of(r.requireOk());
    }

    private record Response(int status, String path, String body) {
        JsonNode requireOk() {
            if (status != 200) {
                throw new IllegalStateException("admin API " + path + " answered " + status);
            }
            try {
                return JSON.readTree(body);
            } catch (RuntimeException e) {
                throw new IllegalStateException("admin API " + path + " answered invalid JSON");
            }
        }
    }

    private Response get(String pathAndQuery) {
        if (adminKey == null || adminKey.isBlank()) {
            throw new IllegalStateException("no admin key: set HARNESS_ADMINKEY (harness.admin-key)");
        }
        String path = pathAndQuery.contains("?") ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.toString().replaceAll("/+$", "") + pathAndQuery))
                .timeout(TIMEOUT).header("X-Admin-Key", adminKey).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), path, response.body());
        } catch (IOException e) {
            throw new IllegalStateException("admin API " + path + " unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling admin API " + path);
        }
    }

    /** Percent-encoding with %20 for spaces (URLEncoder produces '+', which servers decode as a space only in forms). */
    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
