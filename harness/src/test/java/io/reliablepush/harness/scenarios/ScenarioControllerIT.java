package io.reliablepush.harness.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.receiver.ReceiverMode;
import io.reliablepush.harness.receiver.WebhookInbox;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ScenarioControllerIT {

    static volatile CountDownLatch release = new CountDownLatch(0);

    @TestConfiguration
    static class Extra {
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        Scenario testBlocking() {
            return new ScenarioRunnerTest.TestScenario("test-blocking", false, ctx -> {
                ctx.log("waiting");
                release.await();
            });
        }
    }

    static final List<String> CATALOGUE = List.of(
            "delivered-before-timeout", "read-policy-read", "read-policy-delivered-only",
            "not-delivered-single-fallback", "late-delivery-after-fallback", "multi-device", "cancellation",
            "receiver-retries-then-succeeds", "receiver-final-failure", "retries-exhausted-fails",
            "token-refresh-near-expiry", "rate-limited", "log-catalogue", "log-hygiene",
            "worker-crash-inside-window", "worker-crash-outside-window");

    @LocalServerPort
    int port;

    @Autowired
    ScenarioRunner runner;

    @Autowired
    WebhookInbox inbox;

    @Autowired
    HarnessProperties properties;

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void releaseAll() {
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> !runner.running());
        inbox.clear();
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    Map<String, Object> map(String body) {
        return json.readValue(body, new TypeReference<>() {});
    }

    @Test
    void listsCatalogueInOrderWithExternalFlags() throws Exception {
        HttpResponse<String> r = get("/console/api/scenarios");

        assertThat(r.statusCode()).isEqualTo(200);
        List<Map<String, Object>> list = json.readValue(r.body(), new TypeReference<>() {});
        List<String> names = list.stream().map(m -> (String) m.get("name")).toList();
        assertThat(names.subList(0, CATALOGUE.size())).containsExactlyElementsOf(CATALOGUE);
        assertThat(names).last().isEqualTo("test-blocking");
        assertThat(list).allSatisfy(m -> {
            assertThat(m.keySet()).containsExactly("name", "description", "externalControl");
            assertThat((String) m.get("description")).isNotBlank();
            assertThat(m.get("externalControl"))
                    .isEqualTo(((String) m.get("name")).startsWith("worker-crash-"));
        });
    }

    @Test
    void secondRunGets409ProblemAndReportsShowTheLastRun() throws Exception {
        release = new CountDownLatch(1);
        CompletableFuture<HttpResponse<String>> first = http.sendAsync(
                post("/console/api/scenarios/run", "{\"names\":[\"test-blocking\"]}"),
                HttpResponse.BodyHandlers.ofString());
        await().atMost(Duration.ofSeconds(5)).until(runner::running);

        HttpResponse<String> second = http.send(post("/console/api/scenarios/run", "{}"),
                HttpResponse.BodyHandlers.ofString());

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/problem+json"));
        assertThat(map(second.body())).containsEntry("status", 409)
                .hasEntrySatisfying("detail", d -> assertThat((String) d).contains("already in progress"));
        HttpResponse<String> prepareMeanwhile = http.send(
                post("/console/api/scenarios/worker-crash-inside-window/prepare", "{}"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(prepareMeanwhile.statusCode()).isEqualTo(409);

        release.countDown();
        HttpResponse<String> done = first.get(10, TimeUnit.SECONDS);
        assertThat(done.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) map(done.body()).get("results");
        assertThat(results).singleElement().satisfies(res -> {
            assertThat(res).containsEntry("scenario", "test-blocking").containsEntry("status", "PASSED")
                    .containsEntry("failure", null);
            assertThat(res.keySet()).containsExactly("scenario", "status", "durationMs", "failure", "logs");
        });

        HttpResponse<String> summary = get("/console/api/scenarios/report/summary.json");
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(summary.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/json"));
        List<Map<String, Object>> parsed = json.readValue(summary.body(), new TypeReference<>() {});
        assertThat(parsed).extracting(m -> m.get("scenario")).containsExactly("test-blocking");

        HttpResponse<String> junit = get("/console/api/scenarios/report/junit.xml");
        assertThat(junit.statusCode()).isEqualTo(200);
        assertThat(junit.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/xml"));
        assertThat(junit.body()).contains("<testsuite name=\"reliable-push-e2e\"").contains("name=\"test-blocking\"");
    }

    @Test
    void unknownScenarioIs404AndNonExternalPrepareIs400() throws Exception {
        HttpResponse<String> unknown = http.send(post("/console/api/scenarios/run", "{\"names\":[\"nope\"]}"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(map(unknown.body()).get("detail")).asString().contains("nope");

        HttpResponse<String> unknownPrepare = http.send(post("/console/api/scenarios/nope/prepare", "{}"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unknownPrepare.statusCode()).isEqualTo(404);

        HttpResponse<String> notExternal = http.send(post("/console/api/scenarios/test-blocking/prepare", "{}"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(notExternal.statusCode()).isEqualTo(400);

        HttpResponse<String> badId = http.send(post("/console/api/scenarios/worker-crash-inside-window/verify",
                "{\"trackingId\":\"not-a-uuid\"}"), HttpResponse.BodyHandlers.ofString());
        assertThat(badId.statusCode()).isEqualTo(400);
    }

    @Test
    void inFlightEndpointListsHangingWebhooksWithoutSecrets() throws Exception {
        inbox.setMode("shop", ReceiverMode.HANG, Duration.ofSeconds(2));
        UUID id = UUID.randomUUID();
        String body = "{\"trackingId\":\"" + id + "\",\"attempt\":1}";
        long ts = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.app("shop").hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((ts + ".").getBytes(StandardCharsets.US_ASCII));
        String sig = "v1=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        CompletableFuture<HttpResponse<String>> hanging = http.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hooks/shop"))
                        .header("Content-Type", "application/json").header("X-RP-Timestamp", Long.toString(ts))
                        .header("X-RP-Signature", sig).header("Idempotency-Key", id.toString())
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        await().atMost(Duration.ofSeconds(5)).until(() -> inbox.inFlight("shop") == 1);
        HttpResponse<String> r = get("/console/api/inbox/in-flight?appId=shop");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(map(r.body())).containsEntry("appId", "shop").containsEntry("count", 1)
                .containsEntry("trackingIds", List.of(id.toString()));
        assertThat(r.body()).doesNotContain(properties.app("shop").hmacSecret())
                .doesNotContain(properties.app("shop").senderApiKey());
        hanging.get(10, TimeUnit.SECONDS);
        assertThat(map(get("/console/api/inbox/in-flight?appId=shop").body())).containsEntry("count", 0);

        assertThat(get("/console/api/inbox/in-flight?appId=nope").statusCode()).isEqualTo(404);
        assertThat(get("/console/api/inbox/in-flight").statusCode()).isEqualTo(400);
    }
}
