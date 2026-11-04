package io.reliablepush.harness.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.reliablepush.harness.devices.DeviceSimulator.DeviceEventRecord;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Drives the real {@link DeviceSimulator} against a fake backend device API (JDK HttpServer). */
class DeviceSimulatorIT {

    static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

    /** Settable clock. */
    static final class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(T0);

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    record Recorded(String path, String bearer, JsonNode body) {}

    final JsonMapper json = JsonMapper.builder().build();
    final TestClock clock = new TestClock();
    final List<Recorded> requests = new CopyOnWriteArrayList<>();
    final List<DeviceEventRecord> reported = new CopyOnWriteArrayList<>();
    final AtomicInteger tokenCounter = new AtomicInteger();
    final Set<String> validTokens = java.util.concurrent.ConcurrentHashMap.newKeySet();
    final List<String> registeredUsers = new CopyOnWriteArrayList<>();
    volatile Duration validity = Duration.ofMinutes(15);
    volatile int eventsStatus = 200;
    volatile String eventsOutcome = "ACCEPTED";
    volatile boolean stallEvents;
    volatile boolean rejectAllTokens;
    volatile long eventsDelayMs;
    volatile long refreshDelayMs;
    HttpServer server;
    DeviceSimulator simulator;
    String lastIssuedToken;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        simulator = newSimulator(Duration.ofSeconds(5));
        simulator.onEvent(reported::add);
    }

    DeviceSimulator newSimulator(Duration timeout) {
        var props = GrantMinterTest.props(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        return new DeviceSimulator(props, new GrantMinter(props, clock), clock, timeout);
    }

    @AfterEach
    void stop() {
        simulator.close();
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    String newToken() {
        lastIssuedToken = "dev-token-" + tokenCounter.incrementAndGet() + "-secretvalue";
        validTokens.add(lastIssuedToken);
        return lastIssuedToken;
    }

    void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode body = raw.isBlank() ? null : json.readTree(raw);
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String bearer = auth == null ? null : auth.substring("Bearer ".length());
        requests.add(new Recorded(ex.getRequestMethod() + " " + path, bearer, body));
        boolean authed = bearer != null && validTokens.contains(bearer) && !rejectAllTokens;
        switch (ex.getRequestMethod() + " " + path) {
            case "POST /v1/devices" -> {
                registeredUsers.add(body.get("appId").asString());
                String id = body.has("deviceId") ? body.get("deviceId").asString() : UUID.randomUUID().toString();
                reply(ex, 201, "{\"deviceId\":\"" + id + "\",\"deviceToken\":\"" + newToken()
                        + "\",\"expiresAt\":\"" + clock.instant().plus(validity) + "\"}");
            }
            case "POST /v1/devices/token:refresh" -> {
                sleep(refreshDelayMs);
                if (!authed) { reply(ex, 401, "{\"title\":\"Unauthorized\",\"status\":401}"); return; }
                reply(ex, 200, "{\"deviceToken\":\"" + newToken() + "\",\"expiresAt\":\""
                        + clock.instant().plus(validity) + "\"}");
            }
            case "DELETE /v1/devices/me" -> {
                if (!authed) { reply(ex, 401, "{}"); return; }
                validTokens.remove(bearer);
                ex.sendResponseHeaders(204, -1);
                ex.close();
            }
            case "POST /v1/events" -> {
                if (!authed) { reply(ex, 401, "{\"title\":\"Unauthorized\",\"status\":401}"); return; }
                sleep(eventsDelayMs);
                if (stallEvents) {
                    ex.sendResponseHeaders(200, 100);
                    ex.getResponseBody().write('{');
                    ex.getResponseBody().flush();
                    try { Thread.sleep(10_000); } catch (InterruptedException ignored) { }
                    return;
                }
                if (eventsStatus != 200) {
                    ex.getResponseHeaders().add("Retry-After", "7");
                    reply(ex, eventsStatus, "{\"title\":\"x\",\"status\":" + eventsStatus + "}");
                    return;
                }
                List<String> items = new ArrayList<>();
                for (JsonNode e : body.get("events")) {
                    items.add("{\"trackingId\":\"" + e.get("trackingId").asString() + "\",\"outcome\":\""
                            + eventsOutcome + "\"}");
                }
                reply(ex, 200, "{\"accepted\":" + items.size() + ",\"ignored\":0,\"items\":["
                        + String.join(",", items) + "]}");
            }
            default -> reply(ex, 404, "{}");
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    long eventCalls() {
        return requests.stream().filter(r -> r.path().equals("POST /v1/events")).count();
    }

    long count(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).count();
    }

    @Test
    void registerUsesSignedGrant() throws Exception {
        SimulatedDevice d = simulator.register("shop", "user-1", Platform.ANDROID, AndroidMode.V2);

        Recorded reg = requests.get(0);
        assertThat(reg.path()).isEqualTo("POST /v1/devices");
        assertThat(reg.body().get("appId").asString()).isEqualTo("shop");
        assertThat(reg.body().get("platform").asString()).isEqualTo("ANDROID");
        assertThat(reg.body().has("userId")).isFalse();
        String[] parts = reg.body().get("idToken").asString().split("\\.");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(GrantMinterTest.SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII))))
                .isEqualTo(parts[2]);
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.get("aud").asString()).isEqualTo("shop");
        assertThat(claims.get("sub").asString()).isEqualTo("user-1");
        assertThat(d.appId()).isEqualTo("shop");
        assertThat(d.userId()).isEqualTo("user-1");
        assertThat(d.androidMode()).isEqualTo(AndroidMode.V2);
        assertThat(d.online()).isTrue();
        assertThat(d.tokenExpiresAt()).isEqualTo(T0.plus(validity));
        assertThat(simulator.devices("shop", "user-1")).containsExactly(d);
        assertThat(simulator.devices("shop", "other")).isEmpty();
    }

    @Test
    void iosRejectsAndroidMode() {
        assertThatThrownBy(() -> simulator.register("shop", "u", Platform.IOS, AndroidMode.V1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registrationFailureIsReported() {
        server.removeContext("/");
        server.createContext("/", ex -> reply(ex, 401, "{}"));
        assertThatThrownBy(() -> simulator.register("shop", "u", Platform.IOS, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("401");
    }

    @Test
    void deliverAfterPostsDeliveredEvent() {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        UUID tracking = UUID.randomUUID();

        simulator.receivePush(tracking, "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:200ms")));

        assertThat(reported).isEmpty();
        await().atMost(Duration.ofSeconds(3)).until(() -> reported.size() == 1);
        DeviceEventRecord r = reported.get(0);
        assertThat(r.trackingId()).isEqualTo(tracking);
        assertThat(r.deviceId()).isEqualTo(d.deviceId());
        assertThat(r.type()).isEqualTo("DELIVERED");
        assertThat(r.outcome()).isEqualTo("ACCEPTED");
        Recorded call = requests.stream().filter(x -> x.path().equals("POST /v1/events")).findFirst().orElseThrow();
        assertThat(call.bearer()).isEqualTo(lastIssuedToken);
        JsonNode event = call.body().get("events").get(0);
        assertThat(event.get("trackingId").asString()).isEqualTo(tracking.toString());
        assertThat(event.get("type").asString()).isEqualTo("DELIVERED");
        assertThat(Instant.parse(event.get("occurredAt").asString())).isEqualTo(T0);
    }

    @Test
    void defaultBehaviourIsDeliverAfterOneSecond() {
        simulator.register("shop", "u1", Platform.IOS, null);
        long start = System.nanoTime();
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of());
        await().atMost(Duration.ofSeconds(4)).until(() -> reported.size() == 1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }

    @Test
    void readAfterPostsBoth() {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.ANDROID, AndroidMode.V1);
        simulator.receivePush(UUID.randomUUID(), "shop", "u1",
                Map.of(d.deviceId(), DeviceBehaviour.parse("read:100ms:300ms")));

        await().atMost(Duration.ofSeconds(3)).until(() -> reported.size() == 2);
        assertThat(reported).extracting(DeviceEventRecord::type).containsExactly("DELIVERED", "READ");
    }

    @Test
    void lateOutcomeFromBackendIsRecorded() {
        eventsOutcome = "LATE";
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("late:100ms")));
        await().atMost(Duration.ofSeconds(3)).until(() -> reported.size() == 1);
        assertThat(reported.get(0).outcome()).isEqualTo("LATE");
    }

    @Test
    void neverPostsNothing() throws Exception {
        SimulatedDevice never = simulator.register("shop", "u1", Platform.IOS, null);
        SimulatedDevice barrier = simulator.register("shop", "u1", Platform.ANDROID, AndroidMode.V1);
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(
                never.deviceId(), DeviceBehaviour.parse("never"),
                barrier.deviceId(), DeviceBehaviour.parse("deliver:0s")));
        await().atMost(Duration.ofSeconds(3)).until(() -> reported.size() == 1);
        Thread.sleep(1500); // well past the 1 s default a device ignoring `never` would use
        assertThat(reported).extracting(DeviceEventRecord::deviceId).containsExactly(barrier.deviceId());
        assertThat(eventCalls()).isEqualTo(1);
    }

    @Test
    void offlineQueuesThenFlushes() throws Exception {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        simulator.setOnline(d.deviceId(), false);
        assertThat(simulator.device(d.deviceId()).online()).isFalse();
        UUID tracking = UUID.randomUUID();
        simulator.receivePush(tracking, "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:50ms")));
        Thread.sleep(500);
        assertThat(eventCalls()).isZero();
        assertThat(reported).isEmpty();

        clock.now.set(T0.plusSeconds(30));
        simulator.setOnline(d.deviceId(), true);

        await().atMost(Duration.ofSeconds(3)).until(() -> reported.size() == 1);
        assertThat(reported.get(0).trackingId()).isEqualTo(tracking);
        JsonNode event = requests.stream().filter(x -> x.path().equals("POST /v1/events")).findFirst().orElseThrow()
                .body().get("events").get(0);
        assertThat(Instant.parse(event.get("occurredAt").asString())).isEqualTo(T0);
    }

    @Test
    void refreshesOnlyNearExpiry() {
        validity = Duration.ofSeconds(10);
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);

        clock.now.set(T0.plusSeconds(3)); // 7 s left of 10
        simulator.refreshIfNearExpiry(d.deviceId());
        assertThat(count("POST /v1/devices/token:refresh")).isZero();

        clock.now.set(T0.plusSeconds(9)); // 1 s left
        simulator.refreshIfNearExpiry(d.deviceId());
        assertThat(count("POST /v1/devices/token:refresh")).isEqualTo(1);
        assertThat(simulator.device(d.deviceId()).tokenExpiresAt()).isEqualTo(T0.plusSeconds(19));

        simulator.refreshIfNearExpiry(d.deviceId()); // fresh token again
        assertThat(count("POST /v1/devices/token:refresh")).isEqualTo(1);
    }

    @Test
    void refreshesBeforeEventsCallWhenNearExpiry() {
        validity = Duration.ofSeconds(10);
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        clock.now.set(T0.plusSeconds(9));
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:0s")));
        await().atMost(Duration.ofSeconds(3)).until(() -> reported.size() == 1);
        assertThat(count("POST /v1/devices/token:refresh")).isEqualTo(1);
        assertThat(reported.get(0).outcome()).isEqualTo("ACCEPTED");
    }

    @Test
    void tokenNeverExposed() {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        String token = lastIssuedToken;
        assertThat(SimulatedDevice.class.getRecordComponents()).extracting(c -> c.getName().toLowerCase())
                .noneMatch(n -> n.contains("token") && !n.equals("tokenexpiresat"));
        assertThat(d.toString()).doesNotContain(token);
        assertThat(simulator.toString()).doesNotContain(token);
        assertThat(simulator.devices("shop", "u1").toString()).doesNotContain(token);
    }

    @Test
    void rateLimitedIsVisible() {
        eventsStatus = 429;
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:0s")));
        await().atMost(Duration.ofSeconds(3)).until(() -> reported.size() == 1);
        assertThat(reported.get(0).outcome()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void unavailableOn503() {
        eventsStatus = 503;
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:0s")));
        await().atMost(Duration.ofSeconds(3)).until(() -> reported.size() == 1);
        assertThat(reported.get(0).outcome()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void stalledResponseBodyTimesOutAsUnavailable() {
        simulator.close();
        simulator = newSimulator(Duration.ofMillis(600));
        simulator.onEvent(reported::add);
        stallEvents = true;
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:0s")));
        await().atMost(Duration.ofSeconds(4)).until(() -> reported.size() == 1);
        assertThat(reported.get(0).outcome()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void unauthorizedEventsRefreshThenRetry() {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        validTokens.clear();
        validTokens.add("will-be-replaced");
        // the refresh also fails (old token unknown), so the simulator re-registers and then succeeds
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:0s")));
        await().atMost(Duration.ofSeconds(3)).until(() -> reported.size() == 1);
        assertThat(reported.get(0).outcome()).isEqualTo("ACCEPTED");
        assertThat(count("POST /v1/devices/token:refresh")).isEqualTo(1);
        assertThat(count("POST /v1/devices")).isEqualTo(2);
        assertThat(requests.get(requests.size() - 1).path()).isEqualTo("POST /v1/events");
    }

    @Test
    void persistentUnauthorizedGivesUpAfterOneRefreshAndOneReRegistration() {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        rejectAllTokens = true;
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:0s")));
        await().atMost(Duration.ofSeconds(3)).until(() -> reported.size() == 1);
        assertThat(reported.get(0).outcome()).isEqualTo("UNAUTHORIZED");
        assertThat(count("POST /v1/devices/token:refresh")).isEqualTo(1);
        assertThat(count("POST /v1/devices")).isEqualTo(2);
        assertThat(count("POST /v1/events")).isEqualTo(2);
    }

    @Test
    void unregisterCallsDeleteAndForgetsDevice() {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        simulator.unregister(d.deviceId());
        assertThat(count("DELETE /v1/devices/me")).isEqualTo(1);
        assertThat(simulator.devices("shop", "u1")).isEmpty();
        assertThatThrownBy(() -> simulator.device(d.deviceId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readWithSameInstantAlwaysPostsDeliveredFirst() {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        for (int i = 0; i < 10; i++) {
            simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("read:0s:0s")));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> reported.size() == 20);
        for (int i = 0; i < 20; i += 2) {
            assertThat(reported.get(i).type()).isEqualTo("DELIVERED");
            assertThat(reported.get(i + 1).type()).isEqualTo("READ");
            assertThat(reported.get(i + 1).trackingId()).isEqualTo(reported.get(i).trackingId());
        }
    }

    @Test
    void offlineEventsFlushInOrder() throws Exception {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        simulator.setOnline(d.deviceId(), false);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(UUID.randomUUID());
            simulator.receivePush(ids.get(i), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:0s")));
            Thread.sleep(20);
        }
        Thread.sleep(200);
        // a fresh event right after going online must not overtake the queued ones
        simulator.setOnline(d.deviceId(), true);
        UUID fresh = UUID.randomUUID();
        simulator.receivePush(fresh, "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:0s")));
        ids.add(fresh);

        await().atMost(Duration.ofSeconds(5)).until(() -> reported.size() == 7);
        assertThat(reported).extracting(DeviceEventRecord::trackingId).containsExactlyElementsOf(ids);
    }

    @Test
    void goingOfflineMidFlushStopsTheRest() throws Exception {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        simulator.setOnline(d.deviceId(), false);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(UUID.randomUUID());
            simulator.receivePush(ids.get(i), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:0s")));
            Thread.sleep(20);
        }
        eventsDelayMs = 300;
        simulator.setOnline(d.deviceId(), true);
        await().atMost(Duration.ofSeconds(3)).until(() -> eventCalls() >= 1);
        simulator.setOnline(d.deviceId(), false);
        Thread.sleep(1200);
        assertThat(eventCalls()).isLessThanOrEqualTo(2);
        long before = reported.size();
        assertThat(before).isLessThan(5);

        eventsDelayMs = 0;
        simulator.setOnline(d.deviceId(), true);
        await().atMost(Duration.ofSeconds(5)).until(() -> reported.size() == 5);
        Thread.sleep(300);
        assertThat(reported).extracting(DeviceEventRecord::trackingId).containsExactlyElementsOf(ids);
        assertThat(eventCalls()).isEqualTo(5);
    }

    @Test
    void unregisterDropsPendingEventsAndNeverReRegisters() throws Exception {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        rejectAllTokens = true; // any post would 401 -> refresh -> re-register if the device were still alive
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:300ms")));
        simulator.unregister(d.deviceId());
        Thread.sleep(900);
        assertThat(count("POST /v1/devices")).isEqualTo(1);
        assertThat(eventCalls()).isZero();
        assertThat(reported).isEmpty();
    }

    @Test
    void unregisterOfOfflineDeviceDropsQueuedEvents() throws Exception {
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        simulator.setOnline(d.deviceId(), false);
        simulator.receivePush(UUID.randomUUID(), "shop", "u1", Map.of(d.deviceId(), DeviceBehaviour.parse("deliver:0s")));
        Thread.sleep(300);
        simulator.unregister(d.deviceId());
        Thread.sleep(300);
        assertThat(eventCalls()).isZero();
        assertThat(count("POST /v1/devices")).isEqualTo(1);
    }

    @Test
    void devicesReturnsQuicklyWhileRefreshIsBlocked() throws Exception {
        validity = Duration.ofSeconds(10);
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        clock.now.set(T0.plusSeconds(9));
        refreshDelayMs = 1500;
        Thread refresher = Thread.ofVirtual().start(() -> simulator.refreshIfNearExpiry(d.deviceId()));
        await().atMost(Duration.ofSeconds(3)).until(() -> count("POST /v1/devices/token:refresh") == 1);

        long start = System.nanoTime();
        simulator.devices("shop", "u1");
        simulator.device(d.deviceId());
        simulator.setOnline(d.deviceId(), true);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(300));
        refresher.join();
    }

    @Test
    void failedRefreshOtherThan401DoesNotReRegister() {
        validity = Duration.ofSeconds(10);
        SimulatedDevice d = simulator.register("shop", "u1", Platform.IOS, null);
        server.removeContext("/");
        server.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().equals("/v1/devices")) {
                requests.add(new Recorded("POST /v1/devices", null, null));
            }
            reply(ex, 503, "{}");
        });
        clock.now.set(T0.plusSeconds(9));
        simulator.refreshIfNearExpiry(d.deviceId());
        assertThat(count("POST /v1/devices")).isEqualTo(1);
    }
}
