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
@Order(2)
class ReadPolicyRead extends CatalogScenario {

    ReadPolicyRead() {
        super("read-policy-read",
                "Policy NOT_READ, device delivers at 1 s and reads at 2 s: READ and no webhook.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.IOS);
        Instant sent = Instant.now();
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_READ, TIMEOUT_MINUTES, Map.of(d.deviceId(), "read:1s:2s"));
        ctx.awaitState(id, NotificationState.READ, QUICK);
        ctx.expectNoWebhook(id, sent.plus(QUIET_AFTER_SEND));
    }
}
