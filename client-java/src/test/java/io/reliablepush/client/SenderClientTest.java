package io.reliablepush.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.reliablepush.client.internal.Json;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SenderClientTest {

    private static final String KEY = "rp_secret_key_0123456789";
    private static final UUID TID = UUID.fromString("0b0e1f5a-4d6b-4b0a-8f0e-2f6f3c1d9a11");
    private static final String NOTIFICATION = """
            {"trackingId":"0b0e1f5a-4d6b-4b0a-8f0e-2f6f3c1d9a11","userId":"u1","externalId":null,
             "state":"FALLBACK_PENDING","policy":"NOT_READ","timeoutMinutes":5,
             "createdAt":"2026-11-04T10:00:00Z","dueAt":"2026-11-04T10:05:00Z","deliveredAt":null,
             "readAt":null,"closedAt":null,"someNewField":{"a":1}}""";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SenderClient start(Handler h) throws IOException {
        return start(h, Duration.ofSeconds(5));
    }

    private SenderClient start(Handler h, Duration timeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            try {
                h.handle(ex);
            } finally {
                ex.close();
            }
        });
        server.start();
        return SenderClient.builder()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .apiKey(KEY).timeout(timeout).build();
    }

    private static void reply(HttpExchange ex, int status, String contentType, String body, String... headers)
            throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        for (int i = 0; i < headers.length; i += 2) {
            ex.getResponseHeaders().set(headers[i], headers[i + 1]);
        }
        if (status == 204 || b.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
        }
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void registerSendsApiKeyAndBody() throws Exception {
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> ct = new AtomicReference<>();
        AtomicReference<String> sent = new AtomicReference<>();
        AtomicReference<String> line = new AtomicReference<>();
        SenderClient c = start(ex -> {
            key.set(ex.getRequestHeaders().getFirst("X-Api-Key"));
            ct.set(ex.getRequestHeaders().getFirst("Content-Type"));
            sent.set(body(ex));
            line.set(ex.getRequestMethod() + " " + ex.getRequestURI());
            reply(ex, 201, "application/json", NOTIFICATION);
        });

        Notification n = c.register(RegisterRequest.builder().userId("u1").externalId("ord-1")
                .policy(FallbackPolicy.NOT_READ).timeoutMinutes(5).fallback(Map.of("sms", "hello")).build());

        assertThat(line.get()).isEqualTo("POST /v1/notifications");
        assertThat(key.get()).isEqualTo(KEY);
        assertThat(ct.get()).isEqualTo("application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.read(sent.get());
        assertThat(m).containsEntry("userId", "u1").containsEntry("externalId", "ord-1")
                .containsEntry("policy", "NOT_READ").containsEntry("timeoutMinutes", 5L)
                .containsEntry("fallback", Map.of("sms", "hello"));
        assertThat(n.trackingId()).isEqualTo(TID);
        assertThat(n.state()).isEqualTo(NotificationState.FALLBACK_PENDING);
        assertThat(n.policy()).isEqualTo(FallbackPolicy.NOT_READ);
        assertThat(n.timeoutMinutes()).isEqualTo(5);
        assertThat(n.externalId()).isNull();
        assertThat(n.dueAt()).isEqualTo(Instant.parse("2026-11-04T10:05:00Z"));
        assertThat(n.deliveredAt()).isNull();
    }

    @Test
    void registerOmitsOptionalFieldsWhenNull() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        SenderClient c = start(ex -> {
            sent.set(body(ex));
            reply(ex, 201, "application/json", NOTIFICATION);
        });
        c.register(RegisterRequest.builder().userId("u1").fallback(Map.of()).build());
        assertThat(Json.read(sent.get())).isEqualTo(Map.of("userId", "u1", "fallback", Map.of()));
    }

    @Test
    void getUnknownIsEmpty() throws Exception {
        SenderClient c = start(ex -> reply(ex, 404, "application/problem+json", "{\"title\":\"Not Found\",\"status\":404}"));
        assertThat(c.get(TID)).isEmpty();
    }

    @Test
    void getReturnsNotification() throws Exception {
        AtomicReference<String> line = new AtomicReference<>();
        SenderClient c = start(ex -> {
            line.set(ex.getRequestMethod() + " " + ex.getRequestURI());
            reply(ex, 200, "application/json", NOTIFICATION);
        });
        assertThat(c.get(TID)).map(Notification::trackingId).contains(TID);
        assertThat(line.get()).isEqualTo("GET /v1/notifications/" + TID);
    }

    @Test
    void listPassesQueryParams() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        SenderClient c = start(ex -> {
            rawQuery.set(ex.getRequestURI().getRawQuery());
            reply(ex, 200, "application/json", "{\"items\":[" + NOTIFICATION + "],\"nextCursor\":\"abc==\"}");
        });
        NotificationPage p = c.list("us er&1", NotificationState.FALLBACK_PENDING,
                Instant.parse("2026-11-04T10:00:00Z"), 50, "cur+sor/=");
        assertThat(rawQuery.get()).isEqualTo("limit=50&userId=us+er%261&state=FALLBACK_PENDING"
                + "&since=2026-11-04T10%3A00%3A00Z&cursor=cur%2Bsor%2F%3D");
        assertThat(p.items()).hasSize(1);
        assertThat(p.nextCursor()).isEqualTo("abc==");
    }

    @Test
    void listWithoutFiltersOnlySendsLimitAndNullCursorIsNull() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        SenderClient c = start(ex -> {
            rawQuery.set(ex.getRequestURI().getRawQuery());
            reply(ex, 200, "application/json", "{\"items\":[],\"nextCursor\":null}");
        });
        NotificationPage p = c.list(null, null, null, 20, null);
        assertThat(rawQuery.get()).isEqualTo("limit=20");
        assertThat(p.items()).isEmpty();
        assertThat(p.nextCursor()).isNull();
    }

    @Test
    void listRejectsBadLimit() throws Exception {
        SenderClient c = start(ex -> reply(ex, 500, null, ""));
        assertThatThrownBy(() -> c.list(null, null, null, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.list(null, null, null, 101, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelSendsDeleteAndAccepts204() throws Exception {
        AtomicReference<String> line = new AtomicReference<>();
        SenderClient c = start(ex -> {
            line.set(ex.getRequestMethod() + " " + ex.getRequestURI());
            reply(ex, 204, null, "");
        });
        c.cancel(TID);
        assertThat(line.get()).isEqualTo("DELETE /v1/notifications/" + TID);
    }

    @Test
    void cancelConflictThrows409() throws Exception {
        SenderClient c = start(ex -> reply(ex, 409, "application/problem+json",
                "{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"not cancellable\"}"));
        assertThatThrownBy(() -> c.cancel(TID)).isInstanceOfSatisfying(ReliablePushException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.problem()).map(ProblemDetails::title).contains("Conflict");
            assertThat(e.retryAfter()).isEmpty();
        });
    }

    @Test
    void problemDetailsParsed() throws Exception {
        SenderClient c = start(ex -> reply(ex, 422, "application/problem+json",
                "{\"type\":\"about:blank\",\"title\":\"Validation failed\",\"status\":422,\"detail\":\"d\","
                        + "\"errors\":[{\"field\":\"fallback\",\"message\":\"too large\"}]}"));
        assertThatThrownBy(() -> c.register(RegisterRequest.builder().userId("u").fallback(Map.of()).build()))
                .isInstanceOfSatisfying(ReliablePushException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.problem()).contains(new ProblemDetails("about:blank", "Validation failed", 422, "d", "fallback"));
                });
    }

    @Test
    void nonJsonErrorBodyHasNoProblem() throws Exception {
        SenderClient c = start(ex -> reply(ex, 500, "text/html", "<html>boom</html>"));
        assertThatThrownBy(() -> c.get(TID)).isInstanceOfSatisfying(ReliablePushException.class, e -> {
            assertThat(e.status()).isEqualTo(500);
            assertThat(e.problem()).isEmpty();
        });
    }

    @Test
    void rateLimitedExposesRetryAfter() throws Exception {
        SenderClient c = start(ex -> reply(ex, 429, "application/problem+json",
                "{\"title\":\"Too Many Requests\",\"status\":429}", "Retry-After", "7"));
        assertThatThrownBy(() -> c.get(TID)).isInstanceOfSatisfying(ReliablePushException.class, e -> {
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(7));
        });
    }

    @Test
    void serviceUnavailableExposesRetryAfterAndIgnoresHttpDate() throws Exception {
        SenderClient c = start(ex -> reply(ex, 503, null, "", "Retry-After", "3"));
        assertThatThrownBy(() -> c.get(TID)).isInstanceOfSatisfying(ReliablePushException.class,
                e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(3)));
        server.stop(0);
        SenderClient d = start(ex -> reply(ex, 503, null, "", "Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"));
        assertThatThrownBy(() -> d.get(TID)).isInstanceOfSatisfying(ReliablePushException.class, e -> {
            assertThat(e.status()).isEqualTo(503);
            assertThat(e.retryAfter()).isEmpty();
        });
    }

    @Test
    void timeoutIsStatusMinusOne() throws Exception {
        SenderClient c = start(ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, "application/json", NOTIFICATION);
        }, Duration.ofMillis(300));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> c.get(TID)).isInstanceOfSatisfying(ReliablePushException.class, e -> {
            assertThat(e.status()).isEqualTo(-1);
            assertThat(e.problem()).isEmpty();
        });
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void connectionRefusedIsStatusMinusOne() throws Exception {
        SenderClient c = start(ex -> reply(ex, 200, null, ""));
        server.stop(0);
        assertThatThrownBy(() -> c.get(TID)).isInstanceOfSatisfying(ReliablePushException.class,
                e -> assertThat(e.status()).isEqualTo(-1));
    }

    @Test
    void redirectNotFollowed() throws Exception {
        AtomicReference<Integer> hits = new AtomicReference<>(0);
        SenderClient c = start(ex -> {
            hits.updateAndGet(h -> h + 1);
            reply(ex, 302, null, "", "Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere");
        });
        assertThatThrownBy(() -> c.get(TID)).isInstanceOfSatisfying(ReliablePushException.class,
                e -> assertThat(e.status()).isEqualTo(302));
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void apiKeyNeverInExceptionText() throws Exception {
        SenderClient c = start(ex -> reply(ex, 401, "application/problem+json",
                "{\"title\":\"Unauthorized\",\"status\":401}"));
        assertThat(c.toString()).doesNotContain(KEY);
        assertThatThrownBy(() -> c.get(TID)).isInstanceOfSatisfying(ReliablePushException.class, e -> {
            assertThat(e.getMessage()).doesNotContain(KEY);
            assertThat(e.toString()).doesNotContain(KEY);
        });
        server.stop(0);
        SenderClient down = start(ex -> reply(ex, 200, null, ""));
        server.stop(0);
        assertThatThrownBy(() -> down.get(TID)).isInstanceOfSatisfying(ReliablePushException.class, e -> {
            assertThat(e.toString()).doesNotContain(KEY);
            assertThat(e.getCause().toString()).doesNotContain(KEY);
        });
    }

    @Test
    void unknownJsonFieldsIgnored() throws Exception {
        SenderClient c = start(ex -> reply(ex, 200, "application/json", NOTIFICATION));
        assertThat(c.get(TID)).isPresent();
    }

    @Test
    void unknownStateMapsToUnknown() throws Exception {
        SenderClient c = start(ex -> reply(ex, 200, "application/json",
                NOTIFICATION.replace("FALLBACK_PENDING", "SOMETHING_NEW")));
        assertThat(c.get(TID)).map(Notification::state).contains(NotificationState.UNKNOWN);
    }

    @Test
    void malformedSuccessBodyThrowsWithStatus() throws Exception {
        SenderClient c = start(ex -> reply(ex, 200, "application/json", "{\"trackingId\":\"nope\"}"));
        assertThatThrownBy(() -> c.get(TID)).isInstanceOfSatisfying(ReliablePushException.class,
                e -> assertThat(e.status()).isEqualTo(200));
        server.stop(0);
        SenderClient d = start(ex -> reply(ex, 200, "application/json", "not json"));
        assertThatThrownBy(() -> d.list(null, null, null, 10, null)).isInstanceOf(ReliablePushException.class);
        server.stop(0);
        SenderClient f = start(ex -> reply(ex, 200, "application/json", "{\"items\":[1],\"nextCursor\":null}"));
        assertThatThrownBy(() -> f.list(null, null, null, 10, null)).isInstanceOf(ReliablePushException.class);
    }

    @Test
    void baseUrlTrailingSlashAndBuilderValidation() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            reply(ex, 200, "application/json", NOTIFICATION);
            ex.close();
        });
        server.start();
        SenderClient c = SenderClient.builder()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/")).apiKey(KEY).build();
        c.get(TID);
        assertThat(path.get()).isEqualTo("/api/v1/notifications/" + TID);

        URI ok = URI.create("http://127.0.0.1:1");
        assertThatThrownBy(() -> SenderClient.builder().apiKey(KEY).build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SenderClient.builder().baseUrl(ok).build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SenderClient.builder().baseUrl(URI.create("ftp://x")).apiKey(KEY).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SenderClient.builder().baseUrl(ok).apiKey("a\nb").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SenderClient.builder().baseUrl(ok).apiKey(KEY).timeout(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerRequestRequiresUserIdAndFallback() {
        assertThatThrownBy(() -> RegisterRequest.builder().fallback(Map.of()).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RegisterRequest.builder().userId("u").build())
                .isInstanceOf(NullPointerException.class);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }
}
