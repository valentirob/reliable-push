package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.DeviceSimulator.DeviceEventRecord;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(11)
class TokenRefreshNearExpiry extends CatalogScenario {

    TokenRefreshNearExpiry() {
        super("token-refresh-near-expiry",
                "App news (device-token-validity PT20S): the device refreshes its token near expiry and its later "
                        + "events are still accepted.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        Instant registeredAt = Instant.now();
        SimulatedDevice d = ctx.register(NEWS, user, Platform.IOS);
        Instant expires = d.tokenExpiresAt();
        Duration validity = Duration.between(registeredAt, expires);
        ctx.check(validity.compareTo(Duration.ofSeconds(8)) >= 0 && validity.compareTo(Duration.ofSeconds(60)) <= 0,
                "a news device token valid for about 20 s", () -> "valid for " + validity.toSeconds() + " s",
                "compose must set RELIABLEPUSH_APPS_NEWS_DEVICETOKENVALIDITY=PT20S");

        UUID first = ctx.send(NEWS, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "deliver:500ms"));
        expectAccepted(ctx, ctx.awaitDeviceEvents(first, 1, QUICK).get(0));
        ctx.awaitState(first, NotificationState.DELIVERED, QUICK);

        // less than 20 % of the validity left (the simulator refreshes then), yet still valid
        ctx.sleepUntil(expires.minus(validity.dividedBy(5)).plusSeconds(1));
        UUID second = ctx.send(NEWS, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "deliver:200ms"));
        expectAccepted(ctx, ctx.awaitDeviceEvents(second, 1, QUICK).get(0));
        ctx.awaitState(second, NotificationState.DELIVERED, QUICK);

        Instant renewed = ctx.devices().device(d.deviceId()).tokenExpiresAt();
        ctx.check(renewed.isAfter(expires), "a refreshed token expiring after " + expires,
                () -> "token still expires at " + renewed,
                "the simulator refreshes before an events call when < 20 % of the validity is left (spec 7.1)");
        ctx.awaitLogEntry("device.token_refreshed", "rp.device_id", d.deviceId().toString(), QUICK);
    }

    private static void expectAccepted(ScenarioContext ctx, DeviceEventRecord e) {
        ctx.check("ACCEPTED".equals(e.outcome()), "event " + e.type() + " ACCEPTED",
                () -> e.type() + " answered " + e.outcome(), "UNAUTHORIZED means the token or its refresh failed");
    }
}
