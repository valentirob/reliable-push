package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.receiver.ReceiverMode;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Replaces {@code retries-expire-in-window}: with backoff 1+2+4+8 = 15 s all 5 attempts fit the 20 s window. */
@Component
@Order(10)
class RetriesExhaustedFails extends CatalogScenario {

    RetriesExhaustedFails() {
        super("retries-exhausted-fails",
                "Receiver always answers 500: 5 attempts (max-attempts) inside the window, then FALLBACK_FAILED.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        ctx.inbox().setMode(SHOP, ReceiverMode.FAIL_500, Duration.ZERO);
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.ANDROID);
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(d.deviceId(), "never"));
        ctx.awaitState(id, NotificationState.FALLBACK_FAILED, FALLBACK_WAIT);
        ctx.pause(SETTLE);
        List<ReceivedWebhook> calls = ctx.expectWebhookCount(id, 5);
        ctx.check(calls.stream().map(ReceivedWebhook::attempt).toList().equals(List.of(1, 2, 3, 4, 5))
                        && calls.stream().allMatch(w -> w.respondedStatus() == 500),
                "attempts [1..5], all answered 500", () -> ScenarioContext.describe(calls),
                "max-attempts 5 with backoff 1,2,4,8 s inside the 20 s window");
    }
}
