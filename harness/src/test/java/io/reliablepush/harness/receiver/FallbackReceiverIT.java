package io.reliablepush.harness.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import io.reliablepush.harness.config.HarnessProperties;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FallbackReceiverIT {

    @LocalServerPort
    int port;

    @Autowired
    HarnessProperties properties;

    @Autowired
    WebhookInbox inbox;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        inbox.clear();
    }

    private String sign(long ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.app("shop").hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((ts + ".").getBytes(StandardCharsets.US_ASCII));
        return "v1=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String body(UUID id, int attempt) {
        return "{\"trackingId\":\"" + id + "\",\"userId\":\"u-1\",\"fallback\":{\"channel\":\"sms\"},"
                + "\"reason\":\"NOT_DELIVERED\",\"attempt\":" + attempt + "}";
    }

    private HttpResponse<String> post(String appId, String body, String ts, String signature, String idem)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hooks/" + appId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (ts != null) {
            b.header("X-RP-Timestamp", ts);
        }
        if (signature != null) {
            b.header("X-RP-Signature", signature);
        }
        if (idem != null) {
            b.header("Idempotency-Key", idem);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> signedPost(UUID id, int attempt) throws Exception {
        String body = body(id, attempt);
        long ts = Instant.now().getEpochSecond();
        return post("shop", body, Long.toString(ts), sign(ts, body), id.toString());
    }

    @Test
    void okModeRecordsAnd200() throws Exception {
        UUID id = UUID.randomUUID();

        HttpResponse<String> response = signedPost(id, 1);

        assertThat(response.statusCode()).isEqualTo(200);
        List<ReceivedWebhook> received = inbox.received("shop");
        assertThat(received).hasSize(1);
        ReceivedWebhook hook = received.getFirst();
        assertThat(hook.trackingId()).isEqualTo(id);
        assertThat(hook.attempt()).isEqualTo(1);
        assertThat(hook.reason()).isEqualTo("NOT_DELIVERED");
        assertThat(hook.signatureValid()).isTrue();
        assertThat(hook.respondedStatus()).isEqualTo(200);
        assertThat(hook.body()).containsEntry("userId", "u-1");
        assertThat(inbox.forTracking(id)).hasSize(1);
    }

    @Test
    void fail500Mode() throws Exception {
        inbox.setMode("shop", ReceiverMode.FAIL_500, Duration.ZERO);

        assertThat(signedPost(UUID.randomUUID(), 1).statusCode()).isEqualTo(500);
        assertThat(inbox.received("shop").getFirst().respondedStatus()).isEqualTo(500);
    }

    @Test
    void failNextAnswers500ThenFollowsTheMode() throws Exception {
        inbox.failNext("shop", 2);
        UUID id = UUID.randomUUID();

        assertThat(signedPost(id, 1).statusCode()).isEqualTo(500);
        assertThat(signedPost(id, 2).statusCode()).isEqualTo(500);
        assertThat(signedPost(id, 3).statusCode()).isEqualTo(200);
        assertThat(inbox.forTracking(id)).extracting(ReceivedWebhook::respondedStatus).containsExactly(500, 500, 200);
    }

    @Test
    void fail400Mode() throws Exception {
        inbox.setMode("shop", ReceiverMode.FAIL_400, Duration.ZERO);

        assertThat(signedPost(UUID.randomUUID(), 1).statusCode()).isEqualTo(400);
        assertThat(inbox.received("shop").getFirst().respondedStatus()).isEqualTo(400);
    }

    @Test
    void hangModeDelaysResponse() throws Exception {
        inbox.setMode("shop", ReceiverMode.HANG, Duration.ofSeconds(1));
        long start = System.nanoTime();

        HttpResponse<String> response = signedPost(UUID.randomUUID(), 1);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(inbox.received("shop").getFirst().respondedStatus()).isEqualTo(200);
    }

    @Test
    void hangModeIsVisibleAsInFlightAndClearDuringHangLeavesNoStaleEntry() throws Exception {
        inbox.setMode("shop", ReceiverMode.HANG, Duration.ofSeconds(1));
        java.util.concurrent.CompletableFuture<HttpResponse<String>> call =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return signedPost(UUID.randomUUID(), 1);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (inbox.inFlight("shop") == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(inbox.inFlight("shop")).isEqualTo(1);
        assertThat(inbox.received("shop").getFirst().isPending()).isTrue();

        inbox.clear();
        assertThat(call.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);

        assertThat(inbox.inFlight("shop")).isZero();
        assertThat(inbox.received("shop")).isEmpty();
    }

    @Test
    void hangModeInFlightThenCompleted() throws Exception {
        inbox.setMode("shop", ReceiverMode.HANG, Duration.ofSeconds(1));
        java.util.concurrent.CompletableFuture<HttpResponse<String>> call =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return signedPost(UUID.randomUUID(), 1);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (inbox.inFlight("shop") == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(inbox.inFlight("shop")).isEqualTo(1);

        call.get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(inbox.inFlight("shop")).isZero();
        assertThat(inbox.received("shop").getFirst().respondedStatus()).isEqualTo(200);
    }

    @Test
    void invalidSignature401AndRecorded() throws Exception {
        UUID id = UUID.randomUUID();
        String body = body(id, 1);
        long ts = Instant.now().getEpochSecond();

        HttpResponse<String> response = post("shop", body, Long.toString(ts), sign(ts, body + " "), id.toString());

        assertThat(response.statusCode()).isEqualTo(401);
        ReceivedWebhook hook = inbox.received("shop").getFirst();
        assertThat(hook.signatureValid()).isFalse();
        assertThat(hook.respondedStatus()).isEqualTo(401);
        assertThat(hook.trackingId()).isEqualTo(id);
    }

    @Test
    void missingSignatureHeaders401AndRecorded() throws Exception {
        HttpResponse<String> response = post("shop", body(UUID.randomUUID(), 1), null, null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(inbox.received("shop")).singleElement().satisfies(h -> assertThat(h.signatureValid()).isFalse());
    }

    @Test
    void unknownAppIs404AndNotRecorded() throws Exception {
        HttpResponse<String> response = post("nope", "{}", "1", "v1=00", null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(inbox.received("nope")).isEmpty();
    }

    @Test
    void malformedJsonWithValidSignatureIsRecordedWithEmptyBody() throws Exception {
        String body = "not json";
        long ts = Instant.now().getEpochSecond();

        HttpResponse<String> response = post("shop", body, Long.toString(ts), sign(ts, body), null);

        assertThat(response.statusCode()).isEqualTo(200);
        ReceivedWebhook hook = inbox.received("shop").getFirst();
        assertThat(hook.signatureValid()).isTrue();
        assertThat(hook.body()).isEmpty();
        assertThat(hook.trackingId()).isNull();
    }

    @Test
    void idempotencyKeyRecorded() throws Exception {
        UUID id = UUID.randomUUID();

        signedPost(id, 2);

        ReceivedWebhook hook = inbox.received("shop").getFirst();
        assertThat(hook.idempotencyKey()).isEqualTo(id.toString());
        assertThat(hook.attempt()).isEqualTo(2);
    }
}
