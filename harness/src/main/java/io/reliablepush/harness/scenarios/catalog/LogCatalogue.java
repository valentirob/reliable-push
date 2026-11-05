package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(13)
class LogCatalogue extends CatalogScenario {

    static final List<String> ACTIONS = List.of(
            "notification.registered", "tracking.event_accepted", "fallback.claimed", "fallback.dispatched");

    LogCatalogue() {
        super("log-catalogue",
                "One fallback run (NOT_READ, delivered only) leaves notification.registered, tracking.event_accepted, "
                        + "fallback.claimed and fallback.dispatched in the backend log, all with its rp.tracking_id.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.ANDROID);
        UUID id = ctx.send(SHOP, user, FallbackPolicy.NOT_READ, TIMEOUT_MINUTES, Map.of(d.deviceId(), "deliver:1s"));
        ctx.awaitState(id, NotificationState.FALLBACK_TRIGGERED, FALLBACK_WAIT);
        Map<String, Map<String, Object>> found = ctx.awaitLogActions(id, ACTIONS, QUICK);
        for (var e : found.entrySet()) {
            Object tid = e.getValue().get("rp.tracking_id");
            ctx.check(id.toString().equals(tid), e.getKey() + " with rp.tracking_id=" + id,
                    () -> "rp.tracking_id=" + tid, "spec 9.1 correlation");
        }
        // the worker sets one trace.id per claimed row; the API request that registered it has its own
        Object registered = found.get("notification.registered").get("trace.id");
        Object claimed = found.get("fallback.claimed").get("trace.id");
        Object dispatched = found.get("fallback.dispatched").get("trace.id");
        ctx.check(claimed instanceof String c && !c.isBlank() && c.equals(dispatched),
                "fallback.claimed and fallback.dispatched share one worker trace.id",
                () -> "claimed trace.id=" + claimed + ", dispatched trace.id=" + dispatched,
                "spec 9.1: the worker sets a new trace.id per claimed row");
        ctx.check(registered instanceof String r && !r.isBlank() && !r.equals(claimed),
                "notification.registered carries the API request's own trace.id (not the worker's)",
                () -> "registered trace.id=" + registered + ", worker trace.id=" + claimed,
                "spec 9.1: API requests and worker rows are correlated by rp.tracking_id, not by trace.id");
    }
}
