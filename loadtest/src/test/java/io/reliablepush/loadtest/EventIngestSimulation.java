package io.reliablepush.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NFR-2 event ingest: open model, one scenario instance per arrival registers a notification (with the sender's own
 * {@code trackingId}, as the payload jar's {@code TrackingIds.newId()} makes it: a random UUID) for a user with a
 * provisioned device, so every row is tracked ({@code PENDING}, not {@code NO_DEVICE_REGISTERED}), then reports
 * DELIVERED and READ. 500 arrivals/s give 1,000 {@code POST /v1/events}/s. Run explicitly (never in verify):
 * {@code gatling:test -Dgatling.simulationClass=io.reliablepush.loadtest.EventIngestSimulation}.
 */
public class EventIngestSimulation extends Simulation {

    private static final LoadConfig C = LoadConfig.fromEnv(System.getenv());

    private static volatile List<Map<String, Object>> devices = List.of();

    /** Circular feeder over the provisioned devices (rows are available once {@link #before()} has run). */
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final Iterator<Map<String, Object>> FEEDER = new Iterator<>() {
        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public Map<String, Object> next() {
            List<Map<String, Object>> d = devices;
            return d.get(Math.floorMod(NEXT.getAndIncrement(), d.size()));
        }
    };

    private static String eventBody(String type) {
        return "{\"events\":[{\"trackingId\":\"#{trackingId}\",\"type\":\"" + type
                + "\",\"occurredAt\":\"#{occurredAt}\"}]}";
    }

    private static ChainBuilder event(String name, String type) {
        return exec(session -> session.set("occurredAt", java.time.Instant.now().toString()))
                .exec(http(name).post("/v1/events")
                        .header("Authorization", "Bearer #{deviceToken}")
                        .body(StringBody(eventBody(type)))
                        .check(status().is(200)));
    }

    private final ScenarioBuilder arrival = scenario("event-ingest")
            .feed(FEEDER)
            .exec(session -> session.set("trackingId", UUID.randomUUID().toString()))
            .group("register").on(
                    exec(http("register").post("/v1/notifications")
                            .header("X-Api-Key", C.senderApiKey())
                            .body(StringBody("{\"trackingId\":\"#{trackingId}\",\"userId\":\"#{userId}\","
                                    + "\"policy\":\"NOT_READ\",\"timeoutMinutes\":10,\"fallback\":{\"sms\":\"load\"}}"))
                            .check(status().is(201))
                            .check(jsonPath("$.state").is("PENDING"))))
            // no tracked row after a failed register: its events would only measure NOT_FOUND answers
            .exitHereIfFailed()
            .pause(Duration.ofMillis(50))
            // Two occurrences of the group: a group's response time is the sum of its requests, so keeping
            // each event in its own occurrence makes the `events` assertion a per-request figure (pause excluded).
            .group("events").on(event("event-delivered", "DELIVERED"))
            .pause(Duration.ofMillis(100))
            .group("events").on(event("event-read", "READ"));

    private final HttpProtocolBuilder protocol = http.baseUrl(C.apiUrl().toString())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    {
        int nps = C.notificationsPerSecond();
        setUp(arrival.injectOpen(
                        rampUsersPerSec(50).to(nps).during(C.ramp()),
                        constantUsersPerSec(nps).during(C.steady())))
                .protocols(protocol)
                .assertions(
                        details("register").responseTime().percentile(95.0).lte(100),
                        details("register").responseTime().percentile(99.0).lte(250),
                        details("register").failedRequests().percent().lte(0.1),
                        details("events").responseTime().percentile(95.0).lte(100),
                        details("events").responseTime().percentile(99.0).lte(250),
                        details("events").failedRequests().percent().lte(0.1),
                        global().failedRequests().percent().lte(0.1));
    }

    @Override
    public void before() {
        devices = new DeviceProvisioner().provision(C);
        System.out.println("Provisioned " + devices.size() + " devices against " + C.apiUrl());
    }
}
