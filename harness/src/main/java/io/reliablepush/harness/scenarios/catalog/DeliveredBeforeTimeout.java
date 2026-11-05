package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
class DeliveredBeforeTimeout extends CatalogScenario {

    DeliveredBeforeTimeout() {
        super("delivered-before-timeout",
                "Device delivers after 1 s (policy NOT_DELIVERED, timeout 5): DELIVERED and no webhook after 8 s.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.ANDROID);
        Instant sent = Instant.now();
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "deliver:1s"));
        ctx.awaitState(id, NotificationState.DELIVERED, QUICK);
        ctx.expectNoWebhook(id, sent.plus(QUIET_AFTER_SEND));
        ctx.awaitState(id, NotificationState.DELIVERED, QUICK);
    }
}
