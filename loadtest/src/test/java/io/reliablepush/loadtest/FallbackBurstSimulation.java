package io.reliablepush.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Fallback burst: {@code burstSize} NOT_DELIVERED notifications (1 minute timeout, unique user each) at
 * {@code burstPerSecond}; the workers then drain them (measured by {@link BurstDrainProbe}).
 */
public class FallbackBurstSimulation extends Simulation {

    private static final LoadConfig C = LoadConfig.fromEnv(System.getenv());
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private static final Iterator<Map<String, Object>> USERS = new Iterator<>() {
        private final java.util.concurrent.atomic.AtomicLong n = new java.util.concurrent.atomic.AtomicLong();

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public Map<String, Object> next() {
            return Map.of("userId", "burst-" + RUN + "-" + n.getAndIncrement());
        }
    };

    private final ScenarioBuilder burst = scenario("fallback-burst")
            .feed(USERS)
            .exec(http("notify").post("/v1/notifications")
                    .header("X-Api-Key", C.senderApiKey())
                    .body(StringBody("{\"userId\":\"#{userId}\",\"policy\":\"NOT_DELIVERED\",\"timeoutMinutes\":1,"
                            + "\"fallback\":{\"sms\":\"load\"}}"))
                    .check(status().is(201)));

    private final HttpProtocolBuilder protocol = http.baseUrl(C.apiUrl().toString())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    {
        long seconds = Math.max(1, C.burstSize() / C.burstPerSecond());
        setUp(burst.injectOpen(constantUsersPerSec(C.burstPerSecond()).during(Duration.ofSeconds(seconds))))
                .protocols(protocol)
                .assertions(
                        global().failedRequests().percent().lte(0.1),
                        global().responseTime().percentile(99.0).lte(250));
    }
}
