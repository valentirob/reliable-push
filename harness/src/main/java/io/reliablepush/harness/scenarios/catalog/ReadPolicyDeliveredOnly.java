package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
class ReadPolicyDeliveredOnly extends CatalogScenario {

    ReadPolicyDeliveredOnly() {
        super("read-policy-delivered-only",
                "Policy NOT_READ, device only delivers: FALLBACK_TRIGGERED with exactly one webhook.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.ANDROID);
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_READ, TIMEOUT_MINUTES, Map.of(d.deviceId(), "deliver:1s"));
        ctx.awaitState(id, NotificationState.DELIVERED, QUICK);
        ctx.awaitState(id, NotificationState.FALLBACK_TRIGGERED, FALLBACK_WAIT);
        ctx.pause(SETTLE);
        ctx.expectWebhookCount(id, 1);
    }
}
