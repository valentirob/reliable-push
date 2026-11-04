package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.harness.devices.DeviceSimulator.DeviceEventRecord;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(12)
class RateLimited extends CatalogScenario {

    RateLimited() {
        super("rate-limited",
                "App limited (device-per-minute 2): the device's third events call gets 429 with Retry-After.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(LIMITED, user, Platform.ANDROID);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(ctx.send(LIMITED, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                    Map.of(d.deviceId(), "deliver:300ms")));
        }
        List<DeviceEventRecord> events = new ArrayList<>();
        for (UUID id : ids) {
            events.add(ctx.awaitDeviceEvents(id, 1, QUICK).get(0));
        }
        events.sort(Comparator.comparing(DeviceEventRecord::sentAt));
        List<String> outcomes = events.stream().map(DeviceEventRecord::outcome).toList();
        ctx.log("events calls answered " + outcomes);
        ctx.check(outcomes.equals(List.of("ACCEPTED", "ACCEPTED", "RATE_LIMITED")),
                "events calls answered [ACCEPTED, ACCEPTED, RATE_LIMITED]", outcomes::toString,
                "compose must set RELIABLEPUSH_APPS_LIMITED_RATELIMITS_DEVICEPERMINUTE=2");
        Integer retryAfter = events.get(2).retryAfterSeconds();
        ctx.check(retryAfter != null && retryAfter > 0, "429 with a positive Retry-After",
                () -> "Retry-After " + retryAfter, "spec 4.3: 429 carries Retry-After");
    }
}
