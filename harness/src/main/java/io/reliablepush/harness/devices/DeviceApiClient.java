package io.reliablepush.harness.devices;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thin client of the backend's device API. Never throws for HTTP or transport problems: those are a {@link Result}
 * with the status (0 = timeout / I/O failure). Never logs tokens, grants or bodies.
 */
final class DeviceApiClient {

    /** Outcome of a call. {@code value} is set only for the expected success status. */
    record Result<T>(int status, T value, Integer retryAfterSeconds) {
        boolean ok() {
            return value != null;
        }
    }

    record Registered(UUID deviceId, String token, Instant expiresAt) {}

    record Refreshed(String token, Instant expiresAt) {}

    record EventItem(UUID trackingId, String type, Instant occurredAt) {}

    /** Per-item outcomes in request order. */
    record EventsResult(List<String> outcomes) {}

    private final HttpClient http;
    private final URI base;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder().build();

    DeviceApiClient(URI backendUrl, Duration timeout) {
        this.base = backendUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
    }

    Result<Registered> register(String appId, String idToken, Platform platform, UUID deviceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", appId);
        body.put("idToken", idToken);
        body.put("platform", platform.name());
        if (deviceId != null) {
            body.put("deviceId", deviceId.toString());
        }
        return call("POST", "/v1/devices", null, body, 201, n -> new Registered(
                UUID.fromString(n.get("deviceId").asString()),
                n.get("deviceToken").asString(),
                Instant.parse(n.get("expiresAt").asString())));
    }

    Result<Refreshed> refresh(String token) {
        return call("POST", "/v1/devices/token:refresh", token, null, 200, n -> new Refreshed(
                n.get("deviceToken").asString(), Instant.parse(n.get("expiresAt").asString())));
    }

    Result<Boolean> revoke(String token) {
        return call("DELETE", "/v1/devices/me", token, null, 204, n -> Boolean.TRUE);
    }

    Result<EventsResult> postEvents(String token, List<EventItem> events) {
        List<Map<String, Object>> items = events.stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("trackingId", e.trackingId().toString());
                    m.put("type", e.type());
                    m.put("occurredAt", e.occurredAt().toString());
                    return m;
                })
                .toList();
        return call("POST", "/v1/events", token, Map.of("events", items), 200, n -> {
            JsonNode arr = n.get("items");
            List<String> outcomes = new java.util.ArrayList<>();
            if (arr != null && arr.isArray()) {
                arr.forEach(i -> outcomes.add(i.get("outcome").asString()));
            }
            return new EventsResult(outcomes);
        });
    }

    private <T> Result<T> call(String method, String path, String bearer, Object body, int okStatus,
            java.util.function.Function<JsonNode, T> parse) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).method(method, publisher);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        b.header("Accept", "application/json");
        var future = http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            // whole-exchange timeout: HttpRequest.timeout() does not cover a stalled response body
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new Result<>(0, null, null);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new Result<>(0, null, null);
        } catch (ExecutionException e) {
            return new Result<>(0, null, null);
        }
        int status = response.statusCode();
        Integer retryAfter = response.headers().firstValue("Retry-After").map(DeviceApiClient::seconds).orElse(null);
        if (status != okStatus) {
            return new Result<>(status, null, retryAfter);
        }
        try {
            JsonNode node = response.body() == null || response.body().isBlank()
                    ? null : json.readTree(response.body());
            return new Result<>(status, parse.apply(node), retryAfter);
        } catch (RuntimeException e) {
            // 2xx with a body the contract does not allow: treat as a transport-level failure
            return new Result<>(0, null, null);
        }
    }

    private static Integer seconds(String v) {
        try {
            return Integer.valueOf(v.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
