package io.reliablepush.fallback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppFixtures;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.shared.logging.EventCapture;
import io.reliablepush.support.PostgresTestBase;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The production webhook client against WireMock (spec 5): signature and headers, result classification, no redirect
 * following, timeout, and nothing sensitive in logs. The worker profile is on so the test also proves the bean exists
 * wherever the fallback worker runs; the scheduled poll is pushed out to one day so no real claim happens.
 */
@ActiveProfiles({"test", "worker"})
@TestPropertySource(properties = "reliable-push.worker.poll-interval=1d")
class HttpWebhookClientIT extends PostgresTestBase {

    private static final String SECRET_VALUE = "generated-test-hook-secret";
    private static final String USER_ID = "user-pii-4711";
    private static final String PAYLOAD_MARKER = "payload-marker-content";
    private static final String RESPONSE_MARKER = "response-body-marker";

    @Autowired
    WebhookClient client;

    @Autowired
    JsonMapper json;

    @Autowired
    MeterRegistry meters;

    @Value("${info.app.version}")
    String version;

    private WireMockServer receiver;
    private WireMockServer redirectTarget;

    @BeforeEach
    void startServers() {
        receiver = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        receiver.start();
        redirectTarget = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        redirectTarget.start();
    }

    @AfterEach
    void stopServers() {
        receiver.stop();
        redirectTarget.stop();
    }

    private AppConfig app(String httpTimeout) {
        Map<String, Object> properties = new HashMap<>(AppFixtures.minimalApp("whk", "k-whk"));
        properties.put("reliable-push.apps.whk.fallback.url", "http://localhost:" + receiver.port() + "/hook");
        properties.put("reliable-push.apps.whk.fallback.http-timeout", httpTimeout);
        MockEnvironment env = AppFixtures.environment();
        return new AppRegistry(AppFixtures.bind(properties), env).byId("whk").orElseThrow();
    }

    private FallbackCall call() {
        JsonNode fallback = json.createObjectNode().put("text", PAYLOAD_MARKER).put("n", 7);
        return new FallbackCall(UUID.randomUUID(), USER_ID, fallback, "NOT_DELIVERED", 2);
    }

    private LoggedRequest onlyRequest() {
        List<LoggedRequest> requests = receiver.findAll(postRequestedFor(urlEqualTo("/hook")));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private static String hmac(String secret, String timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void success2xx() throws Exception {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(204)));
        FallbackCall call = call();

        WebhookResult result = client.send(app("10s"), call);

        assertThat(result).isEqualTo(new WebhookResult.Success(204));
        LoggedRequest request = onlyRequest();
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        assertThat(request.getHeader("Idempotency-Key")).isEqualTo(call.trackingId().toString());
        assertThat(request.getHeader("User-Agent")).isEqualTo("reliable-push/" + version);
        long timestamp = Long.parseLong(request.getHeader("X-RP-Timestamp"));
        assertThat(Instant.ofEpochSecond(timestamp)).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        // the signature verifies against the exact bytes received
        assertThat(request.getHeader("X-RP-Signature"))
                .isEqualTo(hmac(SECRET_VALUE, request.getHeader("X-RP-Timestamp"), request.getBody()));

        JsonNode body = json.readTree(request.getBody());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("trackingId", "userId", "fallback", "reason", "attempt");
        assertThat(body.get("trackingId").asString()).isEqualTo(call.trackingId().toString());
        assertThat(body.get("userId").asString()).isEqualTo(USER_ID);
        assertThat(body.get("reason").asString()).isEqualTo("NOT_DELIVERED");
        assertThat(body.get("attempt").asInt()).isEqualTo(2);
        assertThat(body.get("fallback").isObject()).isTrue();
        assertThat(body.get("fallback").get("text").asString()).isEqualTo(PAYLOAD_MARKER);
        assertThat(body.get("fallback").get("n").asInt()).isEqualTo(7);
    }

    @Test
    void recordsLatencyTimer() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(200)));
        long before = meters.timer("rp.webhook.latency").count();

        client.send(app("10s"), call());

        assertThat(meters.timer("rp.webhook.latency").count()).isEqualTo(before + 1);
    }

    @Test
    void server500Retryable() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(500)));

        assertThat(client.send(app("10s"), call())).isEqualTo(new WebhookResult.Retryable("http_500"));
    }

    @Test
    void server503Retryable() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(503)));

        assertThat(client.send(app("10s"), call())).isEqualTo(new WebhookResult.Retryable("http_503"));
    }

    @Test
    void badRequest400Final() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(400)));

        assertThat(client.send(app("10s"), call())).isEqualTo(new WebhookResult.Final("http_400"));
    }

    @Test
    void otherClientErrorsAreFinal() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(404)));
        assertThat(client.send(app("10s"), call())).isEqualTo(new WebhookResult.Final("http_404"));

        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(401)));
        assertThat(client.send(app("10s"), call())).isEqualTo(new WebhookResult.Final("http_401"));
    }

    @Test
    void tooMany429Retryable() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(429)));

        assertThat(client.send(app("10s"), call())).isEqualTo(new WebhookResult.Retryable("http_429"));
    }

    @Test
    void requestTimeout408Retryable() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(408)));

        assertThat(client.send(app("10s"), call())).isEqualTo(new WebhookResult.Retryable("http_408"));
    }

    @Test
    void redirect302RetryableNotFollowed() {
        redirectTarget.stubFor(post("/target").willReturn(aResponse().withStatus(200)));
        receiver.stubFor(post("/hook").willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", "http://localhost:" + redirectTarget.port() + "/target")));

        WebhookResult result = client.send(app("10s"), call());

        assertThat(result).isEqualTo(new WebhookResult.Retryable("http_302"));
        redirectTarget.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void slowReceiverTimesOut() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(200).withFixedDelay(12_000)));
        AppConfig app = app("1s");
        long start = System.nanoTime();

        WebhookResult result = client.send(app, call());

        assertThat(result).isEqualTo(new WebhookResult.Retryable("timeout"));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void receiverStallingAfterHeadersTimesOutAndConnectionIsClosed() throws Exception {
        // 200 + Content-Length 100 + 1 body byte, then silence and the socket stays open: the JDK request timeout does
        // not cover the body, so only the whole-exchange bound keeps the worker tick from hanging
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Boolean> clientClosed = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    InputStream in = socket.getInputStream();
                    // drain the request until the end of the headers + body, then answer with a partial body
                    byte[] buffer = new byte[8192];
                    in.read(buffer);
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    int read;
                    do {
                        read = in.read(buffer);
                    } while (read > 0);
                    clientClosed.complete(read == -1);
                } catch (IOException e) {
                    clientClosed.complete(false);
                }
            });
            Map<String, Object> properties = new HashMap<>(AppFixtures.minimalApp("whk", "k-whk"));
            properties.put("reliable-push.apps.whk.fallback.url", "http://localhost:" + server.getLocalPort() + "/hook");
            properties.put("reliable-push.apps.whk.fallback.http-timeout", "1s");
            AppConfig app = new AppRegistry(AppFixtures.bind(properties), AppFixtures.environment()).byId("whk").orElseThrow();
            long start = System.nanoTime();

            WebhookResult result = client.send(app, call());

            assertThat(result).isEqualTo(new WebhookResult.Retryable("timeout"));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            // cancelling released the connection: the receiver sees it closed (EOF)
            assertThat(clientClosed.get(4, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void connectionRefusedRetryableNeverThrows() {
        int closedPort = receiver.port();
        receiver.stop();
        Map<String, Object> properties = new HashMap<>(AppFixtures.minimalApp("whk", "k-whk"));
        properties.put("reliable-push.apps.whk.fallback.url", "http://localhost:" + closedPort + "/hook");
        AppConfig app = new AppRegistry(AppFixtures.bind(properties), AppFixtures.environment()).byId("whk").orElseThrow();

        assertThat(client.send(app, call())).isEqualTo(new WebhookResult.Retryable("io_error"));
    }

    @Test
    void noSecretOrBodyInLogs() {
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(200).withBody(RESPONSE_MARKER)));
        FallbackCall call = call();

        List<LogEvent> events;
        try (EventCapture capture = new EventCapture("io.reliablepush", Level.TRACE)) {
            client.send(app("10s"), call);
            receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(500).withBody(RESPONSE_MARKER)));
            client.send(app("10s"), call);
            receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(400).withBody(RESPONSE_MARKER)));
            client.send(app("10s"), call);
            events = List.copyOf(capture.events());
        }
        String signature = receiver.findAll(postRequestedFor(urlEqualTo("/hook"))).get(0).getHeader("X-RP-Signature");

        // the capture really sees the client's debug lines (status and duration only)
        assertThat(events).extracting(e -> e.getMessage().getFormattedMessage())
                .anySatisfy(m -> assertThat(m).startsWith("Webhook answered with status 500"));
        Set<String> forbidden = Set.of(SECRET_VALUE, signature, USER_ID, PAYLOAD_MARKER, RESPONSE_MARKER);
        assertThat(events).allSatisfy(event -> {
            String text = event.getMessage().getFormattedMessage() + " " + event.getMessage()
                    + " " + (event.getThrown() == null ? "" : event.getThrown().toString())
                    + " " + event.getContextData();
            for (String value : forbidden) {
                assertThat(text).doesNotContain(value);
            }
        });
    }
}
