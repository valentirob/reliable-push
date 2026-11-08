package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.harness.devices.DeviceSimulator.DeviceEventRecord;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.ScenarioContext;
import io.reliablepush.harness.sender.FakeSender;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Spec 1, outcome table: the push is sent with {@code rp_tid} whatever the registration outcome. When no row was
 * stored (a registration that timed out before the backend stored it, or one the backend rejected with 422) the
 * device's DELIVERED is answered NOT_FOUND, nothing is tracked and no fallback ever fires.
 */
@Component
@Order(21)
class RegistrationLost extends CatalogScenario {

    /** Above the API's maximum of 10,080: the backend answers 422 (validation problem, openapi.yaml). */
    static final int INVALID_TIMEOUT_MINUTES = 10_081;

    RegistrationLost() {
        super("registration-lost",
                "Push sent with an unregistered rp_tid (registration lost before it was stored, or rejected 422): "
                        + "the device's DELIVERED is NOT_FOUND, no row, no webhook.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.ANDROID);
        Instant sent = Instant.now();

        FakeSender.SendResult lost = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "deliver:500ms"), null, null, true);
        FakeSender.SendResult rejected = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, INVALID_TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "deliver:500ms"), null, null, false);
        ctx.check(!rejected.registered() && Integer.valueOf(422).equals(rejected.failureStatus()),
                "the registration with timeoutMinutes " + INVALID_TIMEOUT_MINUTES + " rejected with 422",
                () -> rejected.registered() ? "registered" : "HTTP " + rejected.failureStatus(),
                "openapi.yaml: timeoutMinutes is at most 10080 (validation problem 422)");

        for (UUID id : new UUID[] {lost.trackingId(), rejected.trackingId()}) {
            DeviceEventRecord e = ctx.awaitDeviceEvents(id, 1, QUICK).get(0);
            ctx.check("NOT_FOUND".equals(e.outcome()), "DELIVERED for " + id + " answered NOT_FOUND",
                    () -> e.type() + " answered " + e.outcome(),
                    "the push carried an rp_tid the backend never stored (spec 2.5: unknown ids are NOT_FOUND)");
            ctx.check(ctx.notification(id).isEmpty(), "no notification row for " + id, () -> "a row exists",
                    "nothing was registered for this id");
        }
        ctx.expectNoWebhook(lost.trackingId(), sent.plus(QUIET_AFTER_SEND));
        ctx.expectNoWebhook(rejected.trackingId(), Instant.now());
        ctx.check(ctx.notification(lost.trackingId()).isEmpty(), "still no row after the timeout",
                () -> "a row exists", "a late registration would have created one");
    }
}
