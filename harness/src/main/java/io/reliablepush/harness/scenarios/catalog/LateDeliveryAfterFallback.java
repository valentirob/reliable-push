package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.DeviceSimulator.DeviceEventRecord;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(5)
class LateDeliveryAfterFallback extends CatalogScenario {

    LateDeliveryAfterFallback() {
        super("late-delivery-after-fallback",
                "Device delivers only at 10 s, after the fallback fired: state stays FALLBACK_TRIGGERED, event is LATE.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.ANDROID);
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(d.deviceId(), "late:10s"));
        ctx.awaitState(id, NotificationState.FALLBACK_TRIGGERED, FALLBACK_WAIT);
        DeviceEventRecord e = ctx.awaitDeviceEvents(id, 1, QUICK.plus(QUICK)).get(0);
        ctx.check("LATE".equals(e.outcome()), "the late DELIVERED event answered LATE",
                () -> e.type() + " answered " + e.outcome(),
                "events after the fallback was claimed are stored with late=true (spec 2)");
        ctx.pause(SETTLE);
        ctx.awaitState(id, NotificationState.FALLBACK_TRIGGERED, QUICK);
        ctx.expectWebhookCount(id, 1);
    }
}
