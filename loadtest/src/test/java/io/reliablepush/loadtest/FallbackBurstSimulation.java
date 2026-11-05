package io.reliablepush.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fallback burst: {@code burstSize} NOT_DELIVERED notifications (1 minute timeout, unique user each) at
 * {@code burstPerSecond}; the workers then drain them (measured by {@link BurstDrainProbe}).
 *
 * <p>Warm-up: a fresh API replica answers its first minute of peak load slowly (JIT still compiling), so the measured
 * burst is preceded by {@code burstWarmUp} of requests ramping up to the burst rate under their own request name
 * ({@link #WARM_UP}), which no assertion covers. Their notifications fall due only after 60 minutes, long after the drain
 * probe has finished, so they never count as burst rows.
 */
public class FallbackBurstSimulation extends Simulation {

    /** Request name of the measured burst: the only one the assertions cover. */
    static final String MEASURED = "notify";
    /** Request name of the warm-up before the burst (not asserted). */
    static final String WARM_UP = "notify-warm-up";

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

    /** At most 0.1 % failed and p99 at most 250 ms, for the measured requests only (load.sh expects 2 results). */
    static List<Assertion> assertions() {
        return List.of(
                details(MEASURED).failedRequests().percent().lte(0.1),
                details(MEASURED).responseTime().percentile(99.0).lte(250));
    }

    // per instance, so that loading the class (unit tests) needs no environment
    private final LoadConfig c = LoadConfig.fromEnv(System.getenv());

    private ScenarioBuilder notifications(String scenario, String request, int timeoutMinutes) {
        return scenario(scenario)
                .feed(USERS)
                .exec(http(request).post("/v1/notifications")
                        .header("X-Api-Key", c.senderApiKey())
                        .body(StringBody("{\"userId\":\"#{userId}\",\"policy\":\"NOT_DELIVERED\",\"timeoutMinutes\":"
                                + timeoutMinutes + ",\"fallback\":{\"sms\":\"load\"}}"))
                        .check(status().is(201)));
    }

    private final HttpProtocolBuilder protocol = http.baseUrl(c.apiUrl().toString())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    {
        long seconds = Math.max(1, c.burstSize() / c.burstPerSecond());
        setUp(notifications("fallback-burst-warm-up", WARM_UP, 60)
                        .injectOpen(rampUsersPerSec(1).to(c.burstPerSecond()).during(c.burstWarmUp()))
                        .andThen(notifications("fallback-burst", MEASURED, 1)
                                .injectOpen(constantUsersPerSec(c.burstPerSecond()).during(Duration.ofSeconds(seconds)))))
                .protocols(protocol)
                .assertions(assertions());
    }
}
