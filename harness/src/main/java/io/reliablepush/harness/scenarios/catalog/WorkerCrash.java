package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.receiver.ReceiverMode;
import io.reliablepush.harness.scenarios.Scenario;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Worker crash during dispatch, driven by {@code scripts/e2e.sh}: prepare makes the receiver hang 1.5 s (shorter than
 * the 2 s lease, so nothing re-claims before the kill) and registers a never-delivered notification; the script waits
 * for its webhook to be in flight ({@code GET /console/api/inbox/in-flight?appId=shop}), kills both workers, restarts
 * them and calls verify.
 */
abstract class WorkerCrash extends CatalogScenario implements Scenario.External {

    static final Duration HANG = Duration.ofMillis(1500);

    WorkerCrash(String name, String description) {
        super(name, description);
    }

    @Override
    public final boolean externalControl() {
        return true;
    }

    @Override
    public UUID prepare(ScenarioContext ctx) {
        ctx.inbox().setMode(SHOP, ReceiverMode.HANG, HANG);
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.ANDROID);
        return ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(d.deviceId(), "never"));
    }
}
