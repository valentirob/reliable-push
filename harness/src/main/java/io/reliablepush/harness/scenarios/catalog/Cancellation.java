package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(7)
class Cancellation extends CatalogScenario {

    Cancellation() {
        super("cancellation", "The sender cancels at 1 s: CANCELLED and no webhook.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.IOS);
        Instant sent = Instant.now();
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(d.deviceId(), "never"));
        ctx.sleepUntil(sent.plus(Duration.ofSeconds(1)));
        ctx.cancel(id);
        ctx.awaitState(id, NotificationState.CANCELLED, QUICK);
        ctx.expectNoWebhook(id, sent.plus(QUIET_AFTER_SEND));
        ctx.awaitState(id, NotificationState.CANCELLED, QUICK);
    }
}
