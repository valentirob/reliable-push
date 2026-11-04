package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.util.List;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(15)
class WorkerCrashInsideWindow extends WorkerCrash {

    WorkerCrashInsideWindow() {
        super("worker-crash-inside-window",
                "Workers killed while the webhook is in flight and restarted after 3 s: the row is re-claimed "
                        + "(fallback.reclaimed) and re-sent with the same Idempotency-Key; FALLBACK_TRIGGERED.");
    }

    @Override
    public void verify(ScenarioContext ctx, UUID id) throws Exception {
        ctx.track(id, SHOP);
        ctx.awaitState(id, NotificationState.FALLBACK_TRIGGERED, FALLBACK_WAIT);
        ctx.pause(SETTLE);
        List<ReceivedWebhook> calls = ctx.webhooks(id);
        ctx.log(calls.size() + " webhook call(s): " + ScenarioContext.describe(calls));
        ctx.check(calls.size() >= 2 && calls.stream().allMatch(w -> id.toString().equals(w.idempotencyKey())),
                "at least 2 webhook calls, all with Idempotency-Key = " + id,
                () -> calls.size() + " call(s): " + ScenarioContext.describe(calls),
                "the restarted worker must re-claim the row after the lease and re-send once (spec 2)");
        ctx.awaitLogActions(id, List.of("fallback.reclaimed"), QUICK);
    }
}
