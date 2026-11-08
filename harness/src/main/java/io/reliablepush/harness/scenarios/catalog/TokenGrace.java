package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.Notification;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.DeviceSimulator;
import io.reliablepush.harness.devices.DeviceSimulator.DeviceEventRecord;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Spec 2.4 (D6, FR-21): {@code POST /v1/events} accepts a device token expired by at most the app's
 * {@code device-token-events-grace}; past it the answer is 401 and the device keeps the event queued
 * ({@code renewalNeeded}) until {@code ensureRegistered} registers it again and flushes the queue.
 *
 * <p>App {@code grace} of the E2E stack: {@code device-token-validity PT5S}, {@code device-token-events-grace PT15S}.
 * The backend's verifier allows 60 s of clock skew past {@code exp} before a token counts as expired, so the grace
 * window runs from {@code exp + 60 s} to {@code exp + 75 s}. Both notifications are registered at once, while the
 * device is still active for the device check ({@code token_expires_at + grace > now}); the device answers them late,
 * one inside the window and one past it. Takes about 90 s (real time: none of these durations is scaled).
 */
@Component
@Order(22)
class TokenGrace extends CatalogScenario {

    static final String GRACE = "grace";
    /** The verifier's clock skew (DeviceTokenService.SKEW). */
    static final Duration SKEW = Duration.ofSeconds(60);
    /** {@code RELIABLEPUSH_APPS_GRACE_DEVICETOKENEVENTSGRACE} in backend.env.template. */
    static final Duration EVENTS_GRACE = Duration.ofSeconds(15);
    /** Fast clock: 120 "minutes" = 120 s, well after the last event. */
    static final int LONG_TIMEOUT_MINUTES = 120;

    TokenGrace() {
        super("token-grace",
                "App grace (token validity PT5S, events grace PT15S): an expired token inside the grace period "
                        + "delivers (token_state grace); past it the event is 401 and stays queued until "
                        + "ensureRegistered re-registers and flushes it. About 90 s.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        Instant registeredAt = Instant.now();
        SimulatedDevice d = ctx.register(GRACE, user, Platform.IOS);
        Instant expires = d.tokenExpiresAt();
        Duration validity = Duration.between(registeredAt, expires);
        ctx.check(validity.compareTo(Duration.ofSeconds(2)) >= 0 && validity.compareTo(Duration.ofSeconds(30)) <= 0,
                "a grace device token valid for about 5 s", () -> "valid for " + validity.toSeconds() + " s",
                "backend.env.template must set RELIABLEPUSH_APPS_GRACE_DEVICETOKENVALIDITY=PT5S");

        Instant insideGrace = expires.plus(SKEW).plusSeconds(3);
        Instant pastGrace = expires.plus(SKEW).plus(EVENTS_GRACE).plusSeconds(7);
        Instant now = Instant.now();
        UUID inside = ctx.send(GRACE, user, FallbackPolicy.NOT_DELIVERED, LONG_TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "late:" + Duration.between(now, insideGrace).toMillis() + "ms"));
        UUID past = ctx.send(GRACE, user, FallbackPolicy.NOT_DELIVERED, LONG_TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "late:" + Duration.between(now, pastGrace).toMillis() + "ms"));
        for (UUID id : List.of(inside, past)) {
            Notification n = ctx.notification(id).orElseThrow();
            ctx.check(n.state() == NotificationState.PENDING, "registered PENDING (the device is still active)",
                    () -> String.valueOf(n.state()),
                    "registered within token_expires_at + grace; NO_DEVICE_REGISTERED means the send came too late");
        }

        // 1. inside the grace period: accepted with the expired token, no renewal by the device
        DeviceEventRecord ok = ctx.awaitDeviceEvents(inside, 1, untilPlusQuick(insideGrace)).get(0);
        ctx.check("ACCEPTED".equals(ok.outcome()), "DELIVERED with an expired token inside the grace ACCEPTED",
                () -> ok.outcome(), "RELIABLEPUSH_APPS_GRACE_DEVICETOKENEVENTSGRACE=PT15S; see auth.failed in the api log");
        ctx.awaitState(inside, NotificationState.DELIVERED, QUICK);
        Map<String, Object> accepted = ctx.awaitLogActions(inside, List.of("tracking.event_accepted"), QUICK)
                .get("tracking.event_accepted");
        ctx.check("grace".equals(accepted.get("token_state")), "tracking.event_accepted with token_state=grace",
                () -> "token_state=" + accepted.get("token_state"),
                "the event must have been sent after exp + 60 s skew (spec 2.4)");
        Instant unchanged = ctx.devices().device(d.deviceId()).tokenExpiresAt();
        ctx.check(unchanged.equals(expires), "the device did not renew its token on the event path",
                () -> "token now expires at " + unchanged, "spec 3.1: no refresh before or after the events call");

        // 2. past the grace period: 401, the event stays queued, renewalNeeded is set
        DeviceEventRecord refused = ctx.awaitDeviceEvents(past, 1, untilPlusQuick(pastGrace)).get(0);
        ctx.check("UNAUTHORIZED".equals(refused.outcome()), "DELIVERED past the grace answered 401",
                () -> refused.outcome(), "past exp + 60 s skew + 15 s grace the token must be refused");
        SimulatedDevice afterRefusal = ctx.devices().device(d.deviceId());
        ctx.check(afterRefusal.renewalNeeded() && afterRefusal.queuedEvents() == 1,
                "renewalNeeded set and the event kept queued",
                () -> "renewalNeeded=" + afterRefusal.renewalNeeded() + " queued=" + afterRefusal.queuedEvents(),
                "spec 3.1: a 401 keeps the event and sets renewalNeeded");
        Notification stillPending = ctx.notification(past).orElseThrow();
        ctx.check(stillPending.state() == NotificationState.PENDING, "the notification still PENDING",
                () -> String.valueOf(stillPending.state()), "the refused event must not have been recorded");

        // 3. the app calls ensureRegistered: the token is expired -> ID-token path, then the queue is flushed
        DeviceSimulator.EnsureResult result = ctx.devices().ensureRegistered(d.deviceId());
        ctx.log("ensureRegistered: " + result);
        ctx.check(result == DeviceSimulator.EnsureResult.REGISTERED, "ensureRegistered REGISTERED",
                () -> String.valueOf(result), "an expired token takes the ID-token path (spec 3.2)");
        List<DeviceEventRecord> events = ctx.awaitDeviceEvents(past, 2, QUICK);
        ctx.check("ACCEPTED".equals(events.get(1).outcome()), "the queued DELIVERED ACCEPTED after re-registration",
                () -> events.get(1).outcome(), "ensureRegistered must flush the queue with the new token");
        ctx.awaitState(past, NotificationState.DELIVERED, QUICK);
        SimulatedDevice flushed = ctx.devices().device(d.deviceId());
        ctx.check(!flushed.renewalNeeded() && flushed.queuedEvents() == 0, "renewalNeeded cleared, queue empty",
                () -> "renewalNeeded=" + flushed.renewalNeeded() + " queued=" + flushed.queuedEvents(),
                "a successful ensureRegistered clears the flag");
    }

    private static Duration untilPlusQuick(Instant at) {
        Duration left = Duration.between(Instant.now(), at);
        return (left.isNegative() ? Duration.ZERO : left).plus(QUICK);
    }
}
