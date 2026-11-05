package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(4)
class NotDeliveredSingleFallback extends CatalogScenario {

    NotDeliveredSingleFallback() {
        super("not-delivered-single-fallback",
                "Device never reports: exactly one webhook, valid signature, Idempotency-Key = trackingId.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.IOS);
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(d.deviceId(), "never"));
        ctx.awaitState(id, NotificationState.FALLBACK_TRIGGERED, FALLBACK_WAIT);
        ctx.pause(SETTLE);
        ReceivedWebhook w = ctx.expectWebhookCount(id, 1).get(0);
        ctx.check(w.signatureValid(), "a valid X-RP-Signature", () -> "invalid signature",
                "the receiver verifies HMAC-SHA256 over timestamp.body with the app's HMAC secret");
        ctx.check(id.toString().equals(w.idempotencyKey()), "Idempotency-Key = " + id,
                () -> "Idempotency-Key = " + w.idempotencyKey(), "spec 5: Idempotency-Key is the trackingId");
        ctx.check(w.respondedStatus() == 200 && w.attempt() == 1, "attempt 1 answered 200",
                () -> "attempt " + w.attempt() + " answered " + w.respondedStatus(), "receiver mode should be OK");
    }
}
