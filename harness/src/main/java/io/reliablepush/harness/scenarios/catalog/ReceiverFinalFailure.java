package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.receiver.ReceiverMode;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(9)
class ReceiverFinalFailure extends CatalogScenario {

    ReceiverFinalFailure() {
        super("receiver-final-failure", "Receiver answers 400 (permanent): one webhook, FALLBACK_FAILED.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        ctx.inbox().setMode(SHOP, ReceiverMode.FAIL_400, Duration.ZERO);
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.IOS);
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(d.deviceId(), "never"));
        ctx.awaitState(id, NotificationState.FALLBACK_FAILED, FALLBACK_WAIT);
        ctx.pause(SETTLE);
        ReceivedWebhook w = ctx.expectWebhookCount(id, 1).get(0);
        ctx.check(w.respondedStatus() == 400, "the single call answered 400", () -> "answered " + w.respondedStatus(),
                "a 4xx answer is final: no retry");
    }
}
