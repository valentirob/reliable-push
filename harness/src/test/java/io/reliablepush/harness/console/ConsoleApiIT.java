package io.reliablepush.harness.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.devices.DeviceSimulator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The console REST API against a fake backend (sender + device API on a JDK HttpServer). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "harness.poll-interval=1h")
@ActiveProfiles("test")
class ConsoleApiIT {

    static final AtomicInteger tokens = new AtomicInteger();
    static final List<UUID> registered = new ArrayList<>();
    static volatile int registerStatus = 201;
    static volatile int cancelStatus = 204;
    static volatile int deviceStatus = 201;
    static final HttpServer backend = startBackend();

    static HttpServer startBackend() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ConsoleApiIT::handle);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod();
        String p = ex.getRequestURI().getPath();
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (m.equals("POST") && p.equals("/v1/devices") && deviceStatus != 201) {
            if (deviceStatus == 429) {
                ex.getResponseHeaders().add("Retry-After", "7");
            }
            reply(ex, deviceStatus, "{\"title\":\"nope\",\"status\":" + deviceStatus + "}");
        } else if (m.equals("POST") && p.equals("/v1/devices")) {
            reply(ex, 201, "{\"deviceId\":\"" + UUID.randomUUID() + "\",\"deviceToken\":\"tok-"
                    + tokens.incrementAndGet() + "-zzsecretzz\",\"expiresAt\":\""
                    + Instant.now().plus(30, ChronoUnit.DAYS) + "\"}");
        } else if (m.equals("POST") && p.equals("/v1/devices/token:refresh")) {
            reply(ex, 200, "{\"deviceToken\":\"tok-" + tokens.incrementAndGet() + "-zzsecretzz\",\"expiresAt\":\""
                    + Instant.now().plus(30, ChronoUnit.DAYS) + "\"}");
        } else if (m.equals("POST") && p.equals("/v1/events")) {
            reply(ex, 200, "{\"items\":[]}");
        } else if (m.equals("POST") && p.equals("/v1/notifications")) {
            if (registerStatus != 201) {
                if (registerStatus == 429) {
                    ex.getResponseHeaders().add("Retry-After", "9");
                }
                reply(ex, registerStatus, "{\"title\":\"Conflict\",\"status\":" + registerStatus + "}");
                return;
            }
            // the backend echoes the sender's trackingId (lower case)
            java.util.regex.Matcher tid = java.util.regex.Pattern.compile("\"trackingId\":\"([0-9a-f-]{36})\"")
                    .matcher(raw);
            UUID id = tid.find() ? UUID.fromString(tid.group(1)) : UUID.randomUUID();
            synchronized (registered) {
                registered.add(id);
            }
            reply(ex, 201, "{\"trackingId\":\"" + id + "\",\"state\":\"PENDING\",\"userId\":\"u1\","
                    + "\"policy\":\"NOT_DELIVERED\",\"timeoutMinutes\":10,\"createdAt\":\"" + Instant.now()
                    + "\",\"dueAt\":\"" + Instant.now().plus(10, ChronoUnit.MINUTES) + "\"}");
        } else if (m.equals("DELETE") && p.startsWith("/v1/notifications/")) {
            if (cancelStatus == 204) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
            } else {
                reply(ex, cancelStatus, "{\"title\":\"Conflict\",\"status\":" + cancelStatus + "}");
            }
        } else {
            reply(ex, 404, "{\"title\":\"nope\",\"status\":404}");
        }
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status < 300 ? "application/json" : "application/problem+json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("harness.backend-url", () -> "http://127.0.0.1:" + backend.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        backend.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    HarnessProperties properties;

    @Autowired
    DeviceSimulator simulator;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void reset() {
        registerStatus = 201;
        cancelStatus = 204;
        deviceStatus = 201;
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    String createDevice(String userId) throws Exception {
        HttpResponse<String> r = post("/console/api/devices",
                "{\"appId\":\"shop\",\"userId\":\"" + userId + "\",\"platform\":\"ANDROID\",\"androidMode\":\"V2\"}");
        assertThat(r.statusCode()).isEqualTo(201);
        return json.readTree(r.body()).get("deviceId").asString();
    }

    String push(String userId, String perDevice) throws Exception {
        HttpResponse<String> r = post("/console/api/push", "{\"appId\":\"shop\",\"userId\":\"" + userId
                + "\",\"title\":\"Hi\",\"body\":\"Hello\",\"policy\":\"NOT_DELIVERED\",\"timeoutMinutes\":5,"
                + "\"perDevice\":" + perDevice + "}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body()).get("trackingId").asString();
    }

    @Test
    void appsExposeWindowAndTimeScaleButNoSecrets() throws Exception {
        HttpResponse<String> r = get("/console/api/apps");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode shop = json.readTree(r.body()).get(0);
        assertThat(shop.get("appId").asString()).isEqualTo("shop");
        assertThat(shop.get("windowMinutes").asInt()).isEqualTo(20);
        assertThat(shop.get("timeScaleSeconds").asInt()).isEqualTo(60);
        assertThat(r.body()).doesNotContain("key", "secret");
    }

    @Test
    void createDeviceAndList() throws Exception {
        String id = createDevice("list-user");

        JsonNode list = json.readTree(get("/console/api/devices?appId=shop").body());

        boolean found = false;
        for (JsonNode d : list) {
            if (id.equals(d.get("deviceId").asString())) {
                found = true;
                assertThat(d.get("userId").asString()).isEqualTo("list-user");
                assertThat(d.get("kind").asString()).isEqualTo("SIMULATED");
                assertThat(d.get("platform").asString()).isEqualTo("ANDROID");
                assertThat(d.get("androidMode").asString()).isEqualTo("V2");
                assertThat(d.get("tokenExpiresAt").asString()).isNotBlank();
                assertThat(d.get("online").asBoolean()).isTrue();
            }
        }
        assertThat(found).isTrue();
        JsonNode before = deviceById(id);
        int tokensBefore = simulator.issuedTokensForLogHygieneScan().size();
        Thread.sleep(5); // the fake backend stamps expiry with its own clock
        HttpResponse<String> refreshed = post("/console/api/devices/" + id + "/refresh", "{}");
        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(simulator.issuedTokensForLogHygieneScan()).hasSize(tokensBefore + 1);
        assertThat(Instant.parse(json.readTree(refreshed.body()).get("tokenExpiresAt").asString()))
                .isAfter(Instant.parse(before.get("tokenExpiresAt").asString()));
        HttpResponse<String> off = post("/console/api/devices/" + id + "/online", "{\"online\":false}");
        assertThat(json.readTree(off.body()).get("online").asBoolean()).isFalse();
        assertThat(post("/console/api/devices/" + id + "/refresh", "{}").statusCode()).isEqualTo(409);
        assertThat(post("/console/api/devices/" + UUID.randomUUID() + "/refresh", "{}").statusCode()).isEqualTo(404);
        assertThat(post("/console/api/devices/" + id + "/ensure-registered", "{}").statusCode()).isEqualTo(409);
        post("/console/api/devices/" + id + "/online", "{\"online\":true}");
        HttpResponse<String> ensured = post("/console/api/devices/" + id + "/ensure-registered", "{}");
        assertThat(ensured.statusCode()).as(ensured.body()).isEqualTo(200);
        assertThat(json.readTree(ensured.body()).get("result").asString()).isEqualTo("ALREADY_REGISTERED");
        JsonNode device = json.readTree(ensured.body()).get("device");
        assertThat(device.get("renewalNeeded").asBoolean()).isFalse();
        assertThat(device.get("queuedEvents").asInt()).isZero();
        assertThat(ensured.body()).doesNotContain("zzsecretzz");
        assertThat(post("/console/api/devices/" + UUID.randomUUID() + "/ensure-registered", "{}").statusCode())
                .isEqualTo(404);
    }

    JsonNode deviceById(String id) throws Exception {
        for (JsonNode d : json.readTree(get("/console/api/devices").body())) {
            if (id.equals(d.get("deviceId").asString())) {
                return d;
            }
        }
        throw new AssertionError("device not listed");
    }

    @Test
    void pushReturnsTrackingId() throws Exception {
        String dev = createDevice("push-user");

        HttpResponse<String> r = post("/console/api/push", "{\"appId\":\"shop\",\"userId\":\"push-user\","
                + "\"title\":\"Hi\",\"body\":\"Hello\",\"perDevice\":{\"" + dev + "\":\"never\"}}");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode answer = json.readTree(r.body());
        String tid = answer.get("trackingId").asString();
        assertThat(UUID.fromString(tid)).isNotNull();
        assertThat(answer.get("registered").asBoolean()).isTrue();
        assertThat(answer.get("state").asString()).isEqualTo("PENDING");
        assertThat(answer.get("registrationStatus").asInt()).isEqualTo(201);
        JsonNode list = json.readTree(get("/console/api/notifications?appId=shop").body());
        assertThat(list.toString()).contains(tid);
    }

    @Test
    void pushRejectsBadBehaviourAndStillPushesWhenTheRegistrationFails() throws Exception {
        HttpResponse<String> bad = post("/console/api/push", "{\"appId\":\"shop\",\"userId\":\"u\",\"title\":\"t\","
                + "\"body\":\"b\",\"perDevice\":{\"" + UUID.randomUUID() + "\":\"sometimes\"}}");
        assertThat(bad.statusCode()).isEqualTo(400);

        HttpResponse<String> empty = post("/console/api/push", "{\"appId\":\"shop\",\"userId\":\"u\",\"title\":\"\","
                + "\"body\":\"\"}");
        assertThat(empty.statusCode()).as("the payload jar needs a title or a body").isEqualTo(400);

        // spec 1: the push goes out whatever the registration outcome; the answer says it is not tracked
        registerStatus = 409;
        HttpResponse<String> down = post("/console/api/push",
                "{\"appId\":\"shop\",\"userId\":\"u\",\"title\":\"t\",\"body\":\"b\"}");
        assertThat(down.statusCode()).isEqualTo(200);
        JsonNode answer = json.readTree(down.body());
        assertThat(answer.get("registered").asBoolean()).isFalse();
        assertThat(answer.get("state").isNull()).isTrue();
        assertThat(answer.get("registrationStatus").asInt()).isEqualTo(409);
        assertThat(down.body()).doesNotContain("test-sender-key");
        String tid = answer.get("trackingId").asString();
        JsonNode detail = json.readTree(get("/console/api/notifications/" + tid).body());
        assertThat(detail.get("timeline").toString()).contains("registration_missing").contains("push_simulated");
    }

    @Test
    void backendRateLimitIsPassedOnWithRetryAfter() throws Exception {
        deviceStatus = 429;
        HttpResponse<String> device = post("/console/api/devices",
                "{\"appId\":\"shop\",\"userId\":\"rl-user\",\"platform\":\"IOS\"}");
        assertThat(device.statusCode()).as(device.body()).isEqualTo(429);
        assertThat(device.headers().firstValue("Retry-After")).hasValue("7");
        assertThat(device.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(json.readTree(device.body()).get("backendStatus").asInt()).isEqualTo(429);

        // a rate-limited registration does not stop the push (spec 1): it is reported, not passed on as 429
        registerStatus = 429;
        HttpResponse<String> push = post("/console/api/push",
                "{\"appId\":\"shop\",\"userId\":\"u\",\"title\":\"t\",\"body\":\"b\"}");
        assertThat(push.statusCode()).as(push.body()).isEqualTo(200);
        assertThat(json.readTree(push.body()).get("registrationStatus").asInt()).isEqualTo(429);
    }

    @Test
    void deviceRegistrationFailureCarriesTheBackendStatus() throws Exception {
        deviceStatus = 500;
        HttpResponse<String> r = post("/console/api/devices",
                "{\"appId\":\"shop\",\"userId\":\"err-user\",\"platform\":\"IOS\"}");

        assertThat(r.statusCode()).isEqualTo(502);
        JsonNode p = json.readTree(r.body());
        assertThat(p.get("backendStatus").asInt()).isEqualTo(500);
        assertThat(p.get("detail").asString()).contains("500").doesNotContain("unreachable");
    }

    @Test
    void notificationDetailHasTimeline() throws Exception {
        String dev = createDevice("detail-user");
        String tid = push("detail-user", "{\"" + dev + "\":\"never\"}");

        JsonNode detail = json.readTree(get("/console/api/notifications/" + tid).body());

        assertThat(detail.get("notification").get("trackingId").asString()).isEqualTo(tid);
        assertThat(detail.get("notification").get("state").asString()).isEqualTo("PENDING");
        assertThat(detail.get("notification").get("dueAt").asString()).isNotBlank();
        List<String> kinds = new ArrayList<>();
        detail.get("timeline").forEach(e -> kinds.add(e.get("kind").asString()));
        assertThat(kinds).contains("registered", "push_simulated");
        assertThat(get("/console/api/notifications/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get("/console/api/notifications/not-a-uuid").statusCode()).isEqualTo(400);
    }

    @Test
    void cancelCallsBackendAndConflictIs502() throws Exception {
        String tid = push("cancel-user", "{}");

        assertThat(post("/console/api/notifications/" + tid + "/cancel", "{}").statusCode()).isEqualTo(200);
        cancelStatus = 409;
        assertThat(post("/console/api/notifications/" + tid + "/cancel", "{}").statusCode()).isEqualTo(502);
        assertThat(post("/console/api/notifications/" + UUID.randomUUID() + "/cancel", "{}").statusCode())
                .isEqualTo(404);
    }

    @Test
    void deviceNetworkEndpointTogglesProxy() throws Exception {
        assertThat(json.readTree(get("/console/api/device-network").body()).get("online").asBoolean()).isTrue();

        HttpResponse<String> off = post("/console/api/device-network", "{\"online\":false}");

        assertThat(off.statusCode()).isEqualTo(200);
        assertThat(json.readTree(off.body()).get("online").asBoolean()).isFalse();
        assertThat(json.readTree(get("/console/api/device-network").body()).get("online").asBoolean()).isFalse();
        assertThat(post("/console/api/device-network", "{}").statusCode()).isEqualTo(400);
        assertThat(json.readTree(post("/console/api/device-network", "{\"online\":true}").body())
                .get("online").asBoolean()).isTrue();
    }

    @Test
    void payloadPreviewHasThreeShapes() throws Exception {
        UUID tid = UUID.randomUUID();

        HttpResponse<String> r = get("/console/api/payload-preview?trackingId=" + tid + "&title=T%20%3C1%3E&body=B");

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode n = json.readTree(r.body());
        assertThat(n.get("apns").asString()).contains(tid.toString(), "mutable-content");
        assertThat(n.get("fcmV1").asString()).contains(tid.toString(), "\"priority\": \"HIGH\"");
        assertThat(n.get("fcmV2").asString()).contains(tid.toString(), "android_v2");
        assertThat(n.get("iosDebugOpenUrl").asString()).contains("rpsample://debug-push?rp_tid=" + tid);
        assertThat(get("/console/api/payload-preview?trackingId=zzz").statusCode()).isEqualTo(400);
    }

    @Test
    void inboxModeSwitch() throws Exception {
        assertThat(post("/console/api/inbox/mode", "{\"appId\":\"shop\",\"mode\":\"FAIL_500\",\"hangMs\":0}")
                .statusCode()).isEqualTo(200);

        JsonNode inbox = json.readTree(get("/console/api/inbox?appId=shop").body());
        assertThat(inbox.get("mode").get("mode").asString()).isEqualTo("FAIL_500");
        assertThat(inbox.get("calls").isArray()).isTrue();

        post("/console/api/inbox/mode", "{\"appId\":\"shop\",\"mode\":\"HANG\",\"hangMs\":1500}");
        JsonNode hang = json.readTree(get("/console/api/inbox?appId=shop").body());
        assertThat(hang.get("mode").get("mode").asString()).isEqualTo("HANG");
        assertThat(hang.get("mode").get("hangMs").asLong()).isEqualTo(1500);

        assertThat(post("/console/api/inbox/mode", "{\"appId\":\"nope\",\"mode\":\"OK\"}").statusCode())
                .isEqualTo(404);
        assertThat(post("/console/api/inbox/mode", "{\"appId\":\"shop\",\"mode\":\"WAT\"}").statusCode())
                .isEqualTo(400);
        post("/console/api/inbox/mode", "{\"appId\":\"shop\",\"mode\":\"OK\",\"hangMs\":0}");
    }

    @Test
    void logsEndpointFiltersAndNeverFails() throws Exception {
        HttpResponse<String> r = get("/console/api/logs?traceId=abc&trackingId=" + UUID.randomUUID()
                + "&eventAction=x&limit=10");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json.readTree(r.body()).isArray()).isTrue();
    }

    @Test
    void noTokenOrSecretInAnyResponse() throws Exception {
        String dev = createDevice("crawl-user");
        String tid = push("crawl-user", "{\"" + dev + "\":\"deliver:100ms\"}");
        await().atMost(java.time.Duration.ofSeconds(5)).until(
                () -> get("/console/api/notifications/" + tid).body().contains("event_sent"));
        List<String> forbidden = new ArrayList<>(simulator.issuedTokensForLogHygieneScan());
        properties.apps().values().forEach(a -> {
            forbidden.add(a.senderApiKey());
            forbidden.add(a.grantSecret());
            forbidden.add(a.hmacSecret());
        });
        assertThat(forbidden).isNotEmpty();

        // every GET mapping of the application (except the SSE stream, read separately below)
        List<String> paths = new ArrayList<>();
        for (var e : mappings.getHandlerMethods().entrySet()) {
            var info = e.getKey();
            if (info.getMethodsCondition().getMethods().contains(org.springframework.web.bind.annotation.RequestMethod.GET)
                    && info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatternValues().forEach(pattern -> paths.add(concrete(pattern, tid)));
            }
        }
        paths.addAll(List.of("/console/api/devices?userId=crawl-user", "/", "/console.css", "/app.js", "/api.js"));
        for (String name : List.of("devices", "send", "tracking", "inbox", "scenarios", "logs")) {
            paths.add("/views/" + name + ".js");
        }
        assertThat(paths).contains("/console/api/apps", "/console/api/devices", "/console/api/logs");
        for (String path : paths) {
            if (path.equals("/console/api/stream")) {
                continue;
            }
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            for (String secret : forbidden) {
                assertThat(r.body()).as(path).doesNotContain(secret);
            }
            assertThat(r.body()).as(path).doesNotContain("deviceToken");
        }

        // the SSE stream: read about a second of it while a push generates events
        StringBuilder sse = new StringBuilder();
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/console/api/stream"))
                .header("Accept", "text/event-stream").build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = resp.body()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sse.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // closed below
            }
        });
        push("crawl-user", "{\"" + dev + "\":\"deliver:100ms\"}");
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> sse.toString().contains("\"timeline\""));
        Thread.sleep(1000);
        resp.body().close();
        reader.join(2000);
        String bytes = sse.toString();
        for (String secret : forbidden) {
            assertThat(bytes).as("stream").doesNotContain(secret);
        }
    }

    static String concrete(String pattern, String tid) {
        String p = pattern.replace("{id}", tid);
        return p.contains("?") ? p : p
                + (p.equals("/console/api/notifications") || p.equals("/console/api/inbox")
                        || p.equals("/console/api/inbox/in-flight") ? "?appId=shop" : "")
                + (p.equals("/console/api/payload-preview") ? "?trackingId=" + tid : "");
    }

    HttpResponse<String> postWith(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void crossSitePostsAreRejected() throws Exception {
        String mode = "/console/api/inbox/mode";
        String ok = "{\"appId\":\"shop\",\"mode\":\"OK\",\"hangMs\":0}";
        String json = "application/json";

        HttpResponse<String> foreignOrigin = postWith(mode, ok, "Content-Type", json, "Origin", "http://evil.example");
        assertThat(foreignOrigin.statusCode()).isEqualTo(403);
        assertThat(foreignOrigin.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(postWith(mode, ok, "Content-Type", json, "Origin", "http://localhost:" + port).statusCode())
                .as("another origin of the same host").isEqualTo(403);
        assertThat(postWith(mode, ok, "Content-Type", json, "Origin", "null").statusCode()).isEqualTo(403);
        assertThat(postWith(mode, ok, "Content-Type", json, "Sec-Fetch-Site", "cross-site").statusCode()).isEqualTo(403);
        assertThat(postWith(mode, ok, "Content-Type", json, "Sec-Fetch-Site", "same-site").statusCode()).isEqualTo(403);
        assertThat(postWith(mode, ok, "Content-Type", json, "Sec-Fetch-Site", "none").statusCode()).isEqualTo(403);
        assertThat(postWith("/console/api/scenarios/run", "{}", "Content-Type", json, "Origin", "http://evil.example")
                .statusCode()).isEqualTo(403);
    }

    @Test
    void postsNeedAJsonBody() throws Exception {
        String mode = "/console/api/inbox/mode";
        String ok = "{\"appId\":\"shop\",\"mode\":\"OK\",\"hangMs\":0}";

        HttpResponse<String> plain = postWith(mode, ok, "Content-Type", "text/plain");
        assertThat(plain.statusCode()).isEqualTo(415);
        assertThat(plain.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(postWith(mode, ok).statusCode()).as("no Content-Type").isEqualTo(415);
        assertThat(postWith(mode, ok, "Content-Type", "application/x-www-form-urlencoded").statusCode()).isEqualTo(415);
        assertThat(postWith("/console/api/scenarios/run", "{}", "Content-Type", "text/plain").statusCode())
                .isEqualTo(415);
        String tid = push("guard-user", "{}");
        assertThat(postWith("/console/api/notifications/" + tid + "/cancel", "", "Content-Type", "application/json")
                .statusCode()).as("body-less POST without an explicit {}").isEqualTo(400);
        assertThat(postWith("/console/api/scenarios/run", "", "Content-Type", "application/json").statusCode())
                .as("scenario run without an explicit {}").isEqualTo(400);
    }

    @Test
    void sameOriginAndScriptPostsAreAllowed() throws Exception {
        String mode = "/console/api/inbox/mode";
        String ok = "{\"appId\":\"shop\",\"mode\":\"OK\",\"hangMs\":0}";
        String self = "http://127.0.0.1:" + port;

        assertThat(postWith(mode, ok, "Content-Type", "application/json", "Origin", self, "Sec-Fetch-Site",
                "same-origin").statusCode()).as("the console page").isEqualTo(200);
        assertThat(postWith(mode, ok, "Content-Type", "application/json; charset=UTF-8").statusCode())
                .as("curl / scripts: no Origin, no Sec-Fetch-Site").isEqualTo(200);
        assertThat(postWith(mode, ok, "Content-Type", "application/json", "Sec-Fetch-Site", "same-origin")
                .statusCode()).isEqualTo(200);
        assertThat(get("/console/api/apps").statusCode()).isEqualTo(200);
        // the fallback receiver is not a console endpoint: the backend calls it without these headers
        assertThat(postWith("/hooks/shop", "{}", "Content-Type", "text/plain", "Origin", "http://evil.example")
                .statusCode()).isNotIn(403, 415);
    }

    @Test
    void staticConsoleServedWithBanner() throws Exception {
        HttpResponse<String> page = get("/");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(page.body()).contains("Test harness — simulated data, not for production");
        assertThat(page.body()).contains("role=\"status\"");
        for (String name : List.of("devices", "send", "tracking", "inbox", "scenarios", "logs")) {
            assertThat(get("/views/" + name + ".js").statusCode()).as(name).isEqualTo(200);
        }
    }
}
