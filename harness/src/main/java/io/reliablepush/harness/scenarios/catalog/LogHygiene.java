package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Runs last: scans everything the backend logged during the run (plus its own traffic) for the run's secrets. */
@Component
@Order(14)
class LogHygiene extends CatalogScenario {

    LogHygiene() {
        super("log-hygiene",
                "The backend log contains none of the run's sender API keys, grant secrets, HMAC secrets, device "
                        + "tokens or raw user ids.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.IOS);
        UUID delivered = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "deliver:500ms"));
        UUID fallback = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "never"));
        ctx.awaitState(delivered, NotificationState.DELIVERED, QUICK);
        ctx.awaitState(fallback, NotificationState.FALLBACK_TRIGGERED, FALLBACK_WAIT);
        // proves the log is being read at all (an unreadable log would make the scan vacuous)
        ctx.awaitLogActions(fallback, List.of("notification.registered", "fallback.dispatched"), QUICK);
        ctx.pause(Duration.ofSeconds(1)); // one more tail poll for lines written meanwhile

        List<String> leaked = ctx.backendLog().findSecrets(ctx.secrets());
        ctx.check(leaked.isEmpty(),
                "no sender API key, grant secret, HMAC secret, device token or raw user id in the backend log",
                () -> leaked.size() + " found (masked prefixes): " + leaked,
                "spec 9.1 never-logged list; grep harness/build/e2e/logs/ for the masked prefixes");
        ctx.log("backend log clean");
    }
}
