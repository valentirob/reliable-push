package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.util.List;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(16)
class WorkerCrashOutsideWindow extends WorkerCrash {

    WorkerCrashOutsideWindow() {
        super("worker-crash-outside-window",
                "Worker killed while the webhook hangs in flight, workers restarted after 25 s (past the 20 s "
                        + "window): FALLBACK_EXPIRED and no second webhook call.");
    }

    @Override
    public void verify(ScenarioContext ctx, UUID id) throws Exception {
        ctx.track(id, SHOP);
        ctx.awaitState(id, NotificationState.FALLBACK_EXPIRED, FALLBACK_WAIT);
        ctx.pause(SETTLE);
        ctx.expectWebhookCount(id, 1);
        ctx.awaitLogActions(id, List.of("fallback.expired"), QUICK);
    }
}
