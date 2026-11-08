package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.admin.AdminApiClient;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.receiver.ReceiverMode;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The read-only admin API sees what the sender API and the devices produced: notification A is delivered, notification B
 * is never confirmed and its first webhook call fails (500) before the retry succeeds. The user id is fixed and contains
 * a {@code /} and a space on purpose: {@code AdminSmokeE2E} searches for it in the dashboard (URL and hash-route
 * encoding), and the search must find exactly this user's notifications.
 */
@Component
@Order(17)
class AdminDashboard extends CatalogScenario {

    /** Read by AdminSmokeE2E, which runs after the catalogue against the same stack. */
    static final String USER_ID = "admin dash/e2e user";

    private final AdminApiClient admin;

    AdminDashboard(AdminApiClient admin) {
        super("admin-dashboard",
                "Admin API: search lists both notifications, detail shows the 500 then 200 attempts and the DELIVERED event, overview counts them.");
        this.admin = admin;
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = USER_ID;
        SimulatedDevice d = ctx.register(SHOP, user, Platform.IOS);
        ctx.inbox().setMode(SHOP, ReceiverMode.OK, Duration.ZERO);

        UUID a = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(d.deviceId(), "deliver:0ms"));
        ctx.awaitState(a, NotificationState.DELIVERED, QUICK);
        UUID b = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(d.deviceId(), "never"));
        ctx.inbox().failNext(b, 1);
        ctx.awaitState(b, NotificationState.FALLBACK_TRIGGERED, FALLBACK_WAIT);
        ctx.pause(SETTLE);

        JsonNode found = admin.search(SHOP, user);
        List<String> ids = new ArrayList<>();
        found.path("items").forEach(i -> ids.add(i.path("trackingId").asText()));
        ctx.check(ids.contains(a.toString()) && ids.contains(b.toString()), "search lists " + a + " and " + b,
                () -> "search returned " + ids, "GET /v1/admin/notifications?app=shop&userId=<user with a slash and a space>");

        JsonNode detailB = admin.detail(b).orElse(null);
        ctx.check(detailB != null, "detail of " + b, () -> "404", "GET /v1/admin/notifications/{trackingId}");
        JsonNode attempts = detailB.path("attempts");
        String summary = describeAttempts(attempts);
        ctx.check(attempts.size() == 2
                        && "RETRYABLE".equals(attempts.path(0).path("outcome").asText())
                        && attempts.path(0).path("httpStatus").asInt() == 500
                        && "SUCCESS".equals(attempts.path(1).path("outcome").asText())
                        && attempts.path(1).path("httpStatus").asInt() == 200,
                "2 attempts: RETRYABLE 500, then SUCCESS 200", () -> summary,
                "the receiver answers 500 to the first signed call of " + b + "; the attempt rows come from the webhook worker");

        JsonNode detailA = admin.detail(a).orElse(null);
        ctx.check(detailA != null, "detail of " + a, () -> "404", "GET /v1/admin/notifications/{trackingId}");
        JsonNode events = detailA.path("events");
        long delivered = 0;
        boolean ios = true;
        for (JsonNode e : events) {
            if ("DELIVERED".equals(e.path("type").asText())) {
                delivered++;
                ios &= "IOS".equals(e.path("platform").asText());
            }
        }
        long deliveredCount = delivered;
        ctx.check(deliveredCount == 1 && ios && detailA.path("attempts").isEmpty(),
                "one DELIVERED event (platform IOS) and no attempt for " + a, () -> events + " / " + detailA.path("attempts"),
                "device events are stored with the platform of the device");

        JsonNode tile = null;
        for (JsonNode item : admin.overview(SHOP, "1h").path("items")) {
            if (SHOP.equals(item.path("appId").asText())) {
                tile = item;
            }
        }
        JsonNode shop = tile;
        ctx.check(shop != null && shop.path("registered").asLong() >= 2 && shop.path("delivered").asLong() >= 1
                        && shop.path("fallbackTriggered").asLong() >= 1,
                "overview shop 1h: registered >= 2, delivered >= 1, fallbackTriggered >= 1",
                () -> String.valueOf(shop), "GET /v1/admin/overview?app=shop&window=1h");
    }

    private static String describeAttempts(JsonNode attempts) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode a : attempts) {
            sb.append(a.path("attempt").asInt()).append(':').append(a.path("outcome").asText()).append(' ')
                    .append(a.path("httpStatus").asText()).append("; ");
        }
        return sb.length() == 0 ? "no attempts" : sb.toString();
    }
}
