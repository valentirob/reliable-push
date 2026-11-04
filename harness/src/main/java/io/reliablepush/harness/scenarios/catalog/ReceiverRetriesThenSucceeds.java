package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(8)
class ReceiverRetriesThenSucceeds extends CatalogScenario {

    ReceiverRetriesThenSucceeds() {
        super("receiver-retries-then-succeeds",
                "Receiver answers 500 to the first 2 calls: attempts 1..3 with one Idempotency-Key, FALLBACK_TRIGGERED.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.ANDROID);
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(d.deviceId(), "never"));
        // per trackingId, so a stray webhook of an earlier scenario cannot consume it; the first call comes at ~5 s
        ctx.inbox().failNext(id, 2);
        ctx.awaitState(id, NotificationState.FALLBACK_TRIGGERED, FALLBACK_WAIT);
        ctx.pause(SETTLE);
        List<ReceivedWebhook> calls = ctx.expectWebhookCount(id, 3);
        String hint = "backoff 1,2,4,8 s; the attempt number is persisted with each retry outcome";
        ctx.check(calls.stream().map(ReceivedWebhook::attempt).toList().equals(List.of(1, 2, 3)),
                "attempts [1, 2, 3]", () -> ScenarioContext.describe(calls), hint);
        ctx.check(calls.stream().map(ReceivedWebhook::respondedStatus).toList().equals(List.of(500, 500, 200)),
                "statuses [500, 500, 200]", () -> ScenarioContext.describe(calls), hint);
        ctx.check(calls.stream().allMatch(w -> id.toString().equals(w.idempotencyKey()) && w.signatureValid()),
                "every call signed, Idempotency-Key = " + id, () -> ScenarioContext.describe(calls), hint);
    }
}
