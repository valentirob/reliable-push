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
@Order(6)
class MultiDevice extends CatalogScenario {

    MultiDevice() {
        super("multi-device",
                "Two devices of one user, one never reports and one delivers at 2 s: DELIVERED and no webhook.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice silent = ctx.register(SHOP, user, Platform.IOS);
        SimulatedDevice active = ctx.register(SHOP, user, Platform.ANDROID);
        Instant sent = Instant.now();
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(silent.deviceId(), "never", active.deviceId(), "deliver:2s"));
        ctx.awaitState(id, NotificationState.DELIVERED, QUICK);
        ctx.expectNoWebhook(id, sent.plus(QUIET_AFTER_SEND));
    }
}
