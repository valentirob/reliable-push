package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.Notification;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.admin.AdminApiClient;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.receiver.ReceiverMode;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The fallback circuit breaker (FR-18) on the dedicated app {@code circuit}, so no other scenario shares its circuit.
 * The stack tunes the breaker for every app ({@code harness/compose/backend.env.template}): window 30 s, min-due 10,
 * close-min-delivered 3, min-open 5 s (literal durations, not scaled by the fast clock). No other scenario comes near
 * 10 unmet fallbacks of one app within 30 s.
 *
 * <ol>
 *   <li>A burst of {@value #BURST} notifications whose device never confirms opens the circuit. The burst is sent in
 *       parallel; a worker check that sees fewer than min-due of them due lets the tick fire those, but never a row
 *       that came due after the check (spec 4: the claim's due cut-off is the check's instant), so fewer than min-due
 *       calls are guaranteed and the rest end {@code FALLBACK_EXPIRED}.</li>
 *   <li>While the circuit is open, {@value #WHILE_OPEN} more unmet notifications end {@code FALLBACK_EXPIRED} with no
 *       call at all.</li>
 *   <li>The device confirms {@value #CLOSE_MIN_DELIVERED} new notifications: the circuit closes (after min-open), with
 *       {@code OPENED}/{@code tracking_missing} and {@code CLOSED}/{@code delivered_resumed} in its history.</li>
 *   <li>Once the burst's calls left the window, a later unmet notification fires its webhook again.</li>
 * </ol>
 */
@Component
@Order(18)
class FallbackCircuit extends CatalogScenario {

    static final String CIRCUIT = "circuit";
    /** Mirrors RELIABLEPUSH_FALLBACK_CIRCUITBREAKER_MINDUE of the stack. */
    static final int MIN_DUE = 10;
    /** Mirrors RELIABLEPUSH_FALLBACK_CIRCUITBREAKER_CLOSEMINDELIVERED of the stack. */
    static final int CLOSE_MIN_DELIVERED = 3;
    /** Mirrors RELIABLEPUSH_FALLBACK_CIRCUITBREAKER_WINDOW of the stack (real time). */
    static final Duration WINDOW = Duration.ofSeconds(30);
    static final int BURST = 15;
    static final int WHILE_OPEN = 5;
    /** min-open 5 s plus a few worker polls. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(20);

    private static final Set<NotificationState> FINAL = EnumSet.of(NotificationState.READ,
            NotificationState.FALLBACK_TRIGGERED, NotificationState.FALLBACK_FAILED, NotificationState.FALLBACK_EXPIRED,
            NotificationState.CANCELLED);

    private final AdminApiClient admin;

    FallbackCircuit(AdminApiClient admin) {
        super("fallback-circuit",
                "A burst whose device never confirms opens the app's circuit: fallbacks end FALLBACK_EXPIRED without a "
                        + "call; confirmed notifications close it and a later fallback fires again.");
        this.admin = admin;
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(CIRCUIT, user, Platform.IOS);
        ctx.inbox().setMode(CIRCUIT, ReceiverMode.OK, Duration.ZERO);
        String before = circuit(ctx).path("state").asText();
        ctx.check("CLOSED".equals(before), "circuit of " + CIRCUIT + " CLOSED before the burst", () -> before,
                "the app is used by this scenario only; a previous run on a kept stack leaves it CLOSED");

        // 1. the burst opens the circuit
        List<UUID> burst = sendParallel(ctx, user, d, BURST, "never");
        Map<UUID, NotificationState> outcome = awaitFinal(ctx, burst, FALLBACK_WAIT);
        ctx.awaitLogEntry("fallback.circuit_opened", "rp.app_id", CIRCUIT, QUICK);
        ctx.pause(SETTLE);
        long fired = burst.stream().filter(id -> !ctx.webhooks(id).isEmpty()).count();
        long expired = outcome.values().stream().filter(s -> s == NotificationState.FALLBACK_EXPIRED).count();
        ctx.log("burst: " + fired + " call(s), " + expired + " FALLBACK_EXPIRED of " + BURST);
        ctx.check(fired < MIN_DUE && expired == BURST - fired,
                "fewer than " + MIN_DUE + " calls for the burst, every other row FALLBACK_EXPIRED",
                () -> fired + " call(s), states " + outcome.values(),
                "spec 4: the circuit opens once " + MIN_DUE + " unmet fallbacks are due in the window and a tick claims "
                        + "only rows its check counted; spec 5 closes "
                        + "the rest as FALLBACK_EXPIRED; see fallback.circuit_opened and fallback.suppressed");
        JsonNode open = circuit(ctx);
        ctx.check("OPEN".equals(open.path("state").asText()) && open.path("suppressed").asLong() >= expired,
                "circuit OPEN with suppressed >= " + expired, open::toString,
                "GET /v1/admin/fallback-circuits; nothing was delivered since the open, so it cannot close");
        ctx.awaitLogEntry("fallback.suppressed", "rp.app_id", CIRCUIT, QUICK);

        // 2. while the circuit is open, nothing fires
        List<UUID> whileOpen = sendParallel(ctx, user, d, WHILE_OPEN, "never");
        for (UUID id : whileOpen) {
            ctx.awaitState(id, NotificationState.FALLBACK_EXPIRED, FALLBACK_WAIT);
        }
        ctx.pause(SETTLE);
        for (UUID id : whileOpen) {
            ctx.expectWebhookCount(id, 0);
        }
        // the burst's FALLBACK_TRIGGERED rows (if any) count in the due set until they are older than the window
        Instant burstSettled = Instant.now();

        // 3. delivered notifications close it again
        List<UUID> confirmed = sendParallel(ctx, user, d, CLOSE_MIN_DELIVERED, "deliver:0ms");
        for (UUID id : confirmed) {
            ctx.awaitState(id, NotificationState.DELIVERED, QUICK);
        }
        JsonNode closed = awaitCircuitState(ctx, "CLOSED", CLOSE_WAIT);
        ctx.awaitLogEntry("fallback.circuit_closed", "rp.app_id", CIRCUIT, QUICK);
        List<String> history = new ArrayList<>();
        admin.circuitEvents(CIRCUIT).path("items")
                .forEach(e -> history.add(e.path("transition").asText() + "/" + e.path("reason").asText()));
        ctx.check(history.size() >= 2 && history.get(0).equals("CLOSED/delivered_resumed")
                        && history.get(1).equals("OPENED/tracking_missing"),
                "history newest first: CLOSED/delivered_resumed, OPENED/tracking_missing", history::toString,
                "GET /v1/admin/fallback-circuits/circuit/events; circuit " + closed);

        // 4. once the burst's calls left the detection window, a later unmet notification fires its webhook
        ctx.sleepUntil(burstSettled.plus(WINDOW));
        UUID later = ctx.send(CIRCUIT, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "never"));
        ctx.awaitState(later, NotificationState.FALLBACK_TRIGGERED, FALLBACK_WAIT);
        ctx.pause(SETTLE);
        ctx.expectWebhookCount(later, 1);
    }

    private JsonNode circuit(ScenarioContext ctx) {
        Optional<JsonNode> c = admin.circuit(CIRCUIT);
        if (c.isEmpty()) {
            throw ctx.fail("app " + CIRCUIT + " in GET /v1/admin/fallback-circuits", "absent",
                    "the e2e admin key has scope *; the app is configured in harness/compose/backend.env.template");
        }
        return c.get();
    }

    private JsonNode awaitCircuitState(ScenarioContext ctx, String state, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            JsonNode c = circuit(ctx);
            if (state.equals(c.path("state").asText())) {
                ctx.log("circuit of " + CIRCUIT + " is " + state);
                return c;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw ctx.fail("circuit of " + CIRCUIT + " " + state + " within " + timeout.toSeconds() + " s",
                        c.toString(), "spec 4: closes after min-open once close-min-delivered notifications created "
                                + "after the open are delivered; see fallback.circuit_closed in the worker logs");
            }
            Thread.sleep(200);
        }
    }

    /** Sends {@code count} notifications at once (a tight burst: their due times lie close together). */
    private static List<UUID> sendParallel(ScenarioContext ctx, String user, SimulatedDevice d, int count,
                                           String behaviour) throws Exception {
        List<Future<UUID>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> ctx.send(CIRCUIT, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                        Map.of(d.deviceId(), behaviour))));
            }
        }
        List<UUID> ids = new ArrayList<>();
        for (Future<UUID> f : futures) {
            ids.add(f.get());
        }
        return ids;
    }

    /** Polls until every notification is in a final state; fails at the timeout. */
    private static Map<UUID, NotificationState> awaitFinal(ScenarioContext ctx, List<UUID> ids, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Map<UUID, NotificationState> states = new LinkedHashMap<>();
        while (true) {
            for (UUID id : ids) {
                if (!states.containsKey(id)) {
                    ctx.notification(id).map(Notification::state).filter(FINAL::contains)
                            .ifPresent(s -> states.put(id, s));
                }
            }
            if (states.size() == ids.size()) {
                return states;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw ctx.fail("all " + ids.size() + " notifications final within " + timeout.toSeconds() + " s",
                        states.size() + " final: " + states.values(),
                        "the burst ends FALLBACK_EXPIRED (suppressed) or FALLBACK_TRIGGERED (fired before the open)");
            }
            Thread.sleep(200);
        }
    }
}
