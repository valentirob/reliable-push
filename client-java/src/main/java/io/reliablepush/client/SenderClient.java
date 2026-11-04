package io.reliablepush.client;

import io.reliablepush.client.internal.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Client of the reliable-push sender API. Immutable and thread-safe; share one instance. Redirects are never
 * followed. The API key is sent only in the {@code X-Api-Key} header and is never logged or put into messages.
 */
public final class SenderClient {

    private final String base;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;

    SenderClient(URI baseUrl, String apiKey, Duration timeout) {
        this(baseUrl, apiKey, timeout, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build());
    }

    /** Test seam. */
    SenderClient(URI baseUrl, String apiKey, Duration timeout, HttpClient http) {
        String b = baseUrl.toString();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = http;
    }

    public static SenderClientBuilder builder() {
        return new SenderClientBuilder();
    }

    /** Registers a push; idempotent per app when {@code externalId} is set. */
    public Notification register(RegisterRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", r.userId());
        if (r.externalId() != null) {
            body.put("externalId", r.externalId());
        }
        if (r.policy() != null) {
            body.put("policy", r.policy().name());
        }
        if (r.timeoutMinutes() != null) {
            body.put("timeoutMinutes", r.timeoutMinutes());
        }
        body.put("fallback", r.fallback());
        HttpRequest req = request("/v1/notifications")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 201 && resp.statusCode() != 200) {
            throw error(resp);
        }
        return parseNotification(parseBody(resp), resp.statusCode());
    }

    /** Returns the notification, or empty when unknown (or belonging to another app). */
    public Optional<Notification> get(UUID trackingId) {
        HttpResponse<String> resp = send(request("/v1/notifications/" + trackingId).GET().build());
        if (resp.statusCode() == 404) {
            return Optional.empty();
        }
        if (resp.statusCode() != 200) {
            throw error(resp);
        }
        return Optional.of(parseNotification(parseBody(resp), resp.statusCode()));
    }

    /** Lists notifications newest first. {@code userId}, {@code state}, {@code since}, {@code cursor} may be null. */
    public NotificationPage list(String userId, NotificationState state, Instant since, int limit, String cursor) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (state == NotificationState.UNKNOWN) {
            throw new IllegalArgumentException("state UNKNOWN cannot be used as a filter");
        }
        StringBuilder q = new StringBuilder("?limit=").append(limit);
        if (userId != null) {
            q.append("&userId=").append(enc(userId));
        }
        if (state != null) {
            q.append("&state=").append(state.name());
        }
        if (since != null) {
            q.append("&since=").append(enc(since.toString()));
        }
        if (cursor != null) {
            q.append("&cursor=").append(enc(cursor));
        }
        HttpResponse<String> resp = send(request("/v1/notifications" + q).GET().build());
        if (resp.statusCode() != 200) {
            throw error(resp);
        }
        Map<String, Object> page = parseBody(resp);
        if (!(page.get("items") instanceof List<?> raw)) {
            throw malformed(resp.statusCode());
        }
        List<Notification> items = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) {
                throw malformed(resp.statusCode());
            }
            items.add(parseNotification(m, resp.statusCode()));
        }
        Object next = page.get("nextCursor");
        if (next != null && !(next instanceof String)) {
            throw malformed(resp.statusCode());
        }
        return new NotificationPage(items, (String) next);
    }

    /** Cancels an open PENDING/DELIVERED notification. 409 and 404 are thrown as {@link ReliablePushException}. */
    public void cancel(UUID trackingId) {
        HttpResponse<String> resp = send(request("/v1/notifications/" + trackingId).DELETE().build());
        if (resp.statusCode() != 204) {
            throw error(resp);
        }
    }

    @Override
    public String toString() {
        return "SenderClient[" + base + "]";
    }

    private HttpRequest.Builder request(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                .timeout(timeout)
                .header("X-Api-Key", apiKey)
                .header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest req) {
        CompletableFuture<HttpResponse<String>> f =
                http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return f.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new ReliablePushException("sender API call interrupted", -1, null, null, e);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new ReliablePushException("sender API call timed out", -1, null, null, e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof RuntimeException re) {
                throw re;
            }
            if (c instanceof Error err) {
                throw err;
            }
            throw new ReliablePushException("sender API call failed: " + c.getClass().getSimpleName(), -1, null, null, c);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static ReliablePushException malformed(int status) {
        return new ReliablePushException("malformed sender API response (HTTP " + status + ")", status, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(HttpResponse<String> resp) {
        try {
            Object v = Json.read(resp.body());
            if (v instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (IllegalArgumentException e) {
            throw new ReliablePushException("malformed sender API response (HTTP " + resp.statusCode() + ")",
                    resp.statusCode(), null, null, e);
        }
        throw malformed(resp.statusCode());
    }

    private static Notification parseNotification(Map<?, ?> m, int status) {
        try {
            return new Notification(
                    UUID.fromString(str(m, "trackingId", true)),
                    NotificationState.fromWire(str(m, "state", true)),
                    str(m, "userId", true),
                    str(m, "externalId", false),
                    FallbackPolicy.valueOf(str(m, "policy", true)),
                    Math.toIntExact(num(m, "timeoutMinutes")),
                    instant(m, "createdAt", true),
                    instant(m, "dueAt", false),
                    instant(m, "deliveredAt", false),
                    instant(m, "readAt", false),
                    instant(m, "closedAt", false));
        } catch (IllegalArgumentException | ArithmeticException | DateTimeParseException | ClassCastException e) {
            throw new ReliablePushException("malformed sender API response (HTTP " + status + ")", status, null, null, e);
        }
    }

    private static String str(Map<?, ?> m, String key, boolean required) {
        Object v = m.get(key);
        if (v == null && !required) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(key);
    }

    private static long num(Map<?, ?> m, String key) {
        if (m.get(key) instanceof Long l) {
            return l;
        }
        throw new IllegalArgumentException(key);
    }

    private static Instant instant(Map<?, ?> m, String key, boolean required) {
        String s = str(m, key, required);
        return s == null ? null : OffsetDateTime.parse(s).toInstant();
    }

    private static ReliablePushException error(HttpResponse<String> resp) {
        int status = resp.statusCode();
        ProblemDetails problem = parseProblem(resp.body());
        Duration retryAfter = null;
        if (status == 429 || status == 503) {
            retryAfter = resp.headers().firstValue("Retry-After").map(SenderClient::parseSeconds).orElse(null);
        }
        String msg = "sender API returned HTTP " + status;
        if (problem != null && problem.title() != null) {
            msg += ": " + problem.title();
        }
        return new ReliablePushException(msg, status, problem, retryAfter, null);
    }

    private static Duration parseSeconds(String v) {
        try {
            long s = Long.parseLong(v.trim());
            return s < 0 ? null : Duration.ofSeconds(s);
        } catch (NumberFormatException e) {
            return null; // HTTP-date form is not supported
        }
    }

    private static ProblemDetails parseProblem(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            if (!(Json.read(body) instanceof Map<?, ?> m)) {
                return null;
            }
            String field = null;
            if (m.get("errors") instanceof List<?> errs && !errs.isEmpty() && errs.get(0) instanceof Map<?, ?> e
                    && e.get("field") instanceof String f) {
                field = f;
            }
            return new ProblemDetails(asString(m.get("type")), asString(m.get("title")),
                    m.get("status") instanceof Long l ? l.intValue() : 0, asString(m.get("detail")), field);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }
}
