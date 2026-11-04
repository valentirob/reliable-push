package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.Scenario;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Worker crash during dispatch, driven by {@code scripts/e2e.sh} (protocol: {@link Scenario.External}). Prepare makes
 * the receiver hang only the next {@code shop} call, for 8 s, and registers a never-delivered notification. 8 s is
 * shorter than the webhook http-timeout (10 s) and the outside-window restart delay (25 s), and long enough for the
 * script to see the call in flight and kill {@code worker-a} before the receiver answers; {@code worker-b} is
 * stopped before prepare, so no other worker re-claims the row in between.
 */
abstract class WorkerCrash extends CatalogScenario implements Scenario.External {

    static final Duration HANG = Duration.ofSeconds(8);

    WorkerCrash(String name, String description) {
        super(name, description);
    }

    @Override
    public final boolean externalControl() {
        return true;
    }

    @Override
    public UUID prepare(ScenarioContext ctx) {
        ctx.inbox().hangNext(SHOP, 1, HANG);
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.ANDROID);
        return ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(d.deviceId(), "never"));
    }
}
