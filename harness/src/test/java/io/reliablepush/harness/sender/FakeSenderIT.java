package io.reliablepush.harness.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.harness.config.HarnessApp;
import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.devices.DeviceBehaviour;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** FakeSender against a fake sender API (JDK HttpServer) and a recording PushReceiver. */
class FakeSenderIT {

    static final UUID TID = UUID.fromString("6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11");

    record Call(String apiKey, JsonNode body) {}

    record Push(UUID tid, String appId, String userId, Map<UUID, DeviceBehaviour> perDevice) {}

    final JsonMapper json = JsonMapper.builder().build();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final List<Push> pushes = new CopyOnWriteArrayList<>();
    volatile int status = 201;
    HttpServer server;
    FakeSender sender;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/notifications", this::handle);
        server.start();
        var props = new HarnessProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Path.of("target"), Map.of("shop", new HarnessApp("sender-key", "g", "h", "https://shop.test.invalid")));
        sender = new FakeSender(props, (tid, app, user, per) -> pushes.add(new Push(tid, app, user, per)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call(ex.getRequestHeaders().getFirst("X-Api-Key"), json.readTree(raw)));
        String tid = json.readTree(raw).path("trackingId").asString(TID.toString());
        String resp = status == 201
                ? "{\"trackingId\":\"" + tid + "\",\"state\":\"PENDING\",\"userId\":\"u1\",\"policy\":\"NOT_DELIVERED\","
                        + "\"timeoutMinutes\":10,\"createdAt\":\"2026-11-04T10:00:00Z\",\"dueAt\":\"2026-11-04T10:10:00Z\"}"
                : "{\"title\":\"Bad\",\"status\":" + status + "}";
        byte[] b = resp.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status == 201 ? "application/json" : "application/problem+json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void sendGeneratesTheIdRegistersWithItThenSimulatesPush() {
        UUID dev = UUID.randomUUID();
        Map<UUID, DeviceBehaviour> per = Map.of(dev, DeviceBehaviour.parse("never"));

        FakeSender.SendResult r = sender.send(new SendPushCommand("shop", "u1", "Title", "Hello body",
                FallbackPolicy.NOT_READ, 7, null, per));

        assertThat(calls).hasSize(1);
        Call c = calls.get(0);
        String sentId = c.body().get("trackingId").asString();
        assertThat(io.reliablepush.payload.TrackingIds.isValid(sentId)).isTrue();
        assertThat(sentId).isEqualTo(sentId.toLowerCase(java.util.Locale.ROOT));
        assertThat(r.trackingId()).isEqualTo(UUID.fromString(sentId));
        assertThat(r.registered()).isTrue();
        assertThat(r.notification().trackingId()).isEqualTo(r.trackingId());
        assertThat(r.failureStatus()).isNull();
        assertThat(c.apiKey()).isEqualTo("sender-key");
        assertThat(c.body().get("userId").asString()).isEqualTo("u1");
        assertThat(c.body().get("policy").asString()).isEqualTo("NOT_READ");
        assertThat(c.body().get("timeoutMinutes").asInt()).isEqualTo(7);
        assertThat(c.body().at("/fallback/channel").asString()).isEqualTo("sms");
        assertThat(c.body().at("/fallback/text").asString()).isEqualTo("Hello body");
        assertThat(pushes).containsExactly(new Push(r.trackingId(), "shop", "u1", per));
    }

    @Test
    void everySendGetsANewId() {
        FakeSender.SendResult a = sender.send(new SendPushCommand("shop", "u1", "T", "B", null, null, null, null));
        FakeSender.SendResult b = sender.send(new SendPushCommand("shop", "u1", "T", "B", null, null, null, null));

        assertThat(a.trackingId()).isNotEqualTo(b.trackingId());
    }

    @Test
    void ownTrackingIdAndExternalIdAreSentAsGiven() {
        sender.send(new SendPushCommand("shop", "u1", "T", "B", null, null, null, null, TID, "order-7", false));

        assertThat(calls.get(0).body().get("trackingId").asString()).isEqualTo(TID.toString());
        assertThat(calls.get(0).body().get("externalId").asString()).isEqualTo("order-7");
        assertThat(pushes).extracting(Push::tid).containsExactly(TID);
    }

    @Test
    void explicitFallbackIsSentAsGiven() {
        sender.send(new SendPushCommand("shop", "u1", "T", "B", null, null,
                Map.of("channel", "email", "to", "a@b.c"), null));

        assertThat(calls.get(0).body().at("/fallback/channel").asString()).isEqualTo("email");
        assertThat(calls.get(0).body().has("policy")).isFalse();
        assertThat(pushes).hasSize(1);
    }

    @Test
    void registrationFailureStillPushes() {
        status = 400;

        FakeSender.SendResult r = sender.send(new SendPushCommand("shop", "u1", "T", "B", null, null, null, null));

        assertThat(r.registered()).isFalse();
        assertThat(r.skipped()).isFalse();
        assertThat(r.failureStatus()).isEqualTo(400);
        assertThat(pushes).extracting(Push::tid).containsExactly(r.trackingId());
    }

    @Test
    void unreachableBackendStillPushes() {
        server.stop(0);

        FakeSender.SendResult r = sender.send(new SendPushCommand("shop", "u1", "T", "B", null, null, null, null));

        assertThat(r.failureStatus()).isEqualTo(-1);
        assertThat(pushes).extracting(Push::tid).containsExactly(r.trackingId());
    }

    @Test
    void skippedRegistrationCallsNothingAndPushes() {
        FakeSender.SendResult r = sender.send(
                new SendPushCommand("shop", "u1", "T", "B", null, null, null, null, TID, null, true));

        assertThat(calls).isEmpty();
        assertThat(r).isEqualTo(new FakeSender.SendResult(TID, null, null, true));
        assertThat(pushes).extracting(Push::tid).containsExactly(TID);
    }

    @Test
    void listenerSeesTheResultBeforeThePush() {
        List<FakeSender.Sent> seen = new CopyOnWriteArrayList<>();
        sender.onSent(s -> {
            assertThat(pushes).isEmpty();
            seen.add(s);
        });
        sender.onSent(s -> {
            throw new IllegalStateException("a listener never breaks sending");
        });

        FakeSender.SendResult r = sender.send(new SendPushCommand("shop", "u1", "T", "B", null, null, null,
                Map.of(UUID.randomUUID(), DeviceBehaviour.parse("never"))));

        assertThat(seen).singleElement().satisfies(s -> {
            assertThat(s.appId()).isEqualTo("shop");
            assertThat(s.trackingId()).isEqualTo(r.trackingId());
            assertThat(s.notification()).isEqualTo(r.notification());
            assertThat(s.deviceCount()).isEqualTo(1);
        });
        assertThat(pushes).hasSize(1);
    }

    @Test
    void payloadTheJarRejectsIsNeitherRegisteredNorPushed() {
        assertThatThrownBy(() -> sender.send(new SendPushCommand("shop", "u1", "", "", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
        assertThat(pushes).isEmpty();
    }

    @Test
    void unknownAppFailsWithoutCallingBackend() {
        assertThatThrownBy(() -> sender.send(new SendPushCommand("nope", "u1", "T", "B", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
        assertThat(pushes).isEmpty();
    }
}
