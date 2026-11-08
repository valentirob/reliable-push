package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.Notification;
import io.reliablepush.client.NotificationState;
import io.reliablepush.client.ReliablePushException;
import io.reliablepush.harness.devices.DeviceBehaviour;
import io.reliablepush.harness.devices.DeviceSimulator.DeviceEventRecord;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.sender.FakeSender;
import io.reliablepush.harness.scenarios.ScenarioContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Spec 2.1/2.2 (D4): a user without an active device gets no tracking and never a fallback. Covers a user who never
 * registered and one whose only device was revoked, the final state (cancel is 409, a later DELIVERED is LATE and
 * changes nothing, a device registered later does not reopen it), and the contrast: the same user once registered.
 */
@Component
@Order(19)
class NoDeviceRegistered extends CatalogScenario {

    NoDeviceRegistered() {
        super("no-device-registered",
                "Users without an active device (never registered, or revoked): 201 NO_DEVICE_REGISTERED, final "
                        + "(cancel 409, a later event LATE), no webhook; once a device is registered the next push is "
                        + "tracked.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        // 1. a user who never registered a device
        String never = ctx.userId(1);
        Instant sent = Instant.now();
        FakeSender.SendResult r = ctx.send(SHOP, never, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES, Map.of(),
                null, null, false);
        UUID untracked = r.trackingId();
        expectNoDevice(ctx, r);

        // 2. a user whose only device was revoked (logout)
        String revoked = ctx.userId(2);
        SimulatedDevice gone = ctx.register(SHOP, revoked, Platform.ANDROID);
        ctx.devices().unregister(gone.deviceId());
        ctx.log("revoked device " + gone.deviceId());
        FakeSender.SendResult afterLogout = ctx.send(SHOP, revoked, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(), null, null, false);
        expectNoDevice(ctx, afterLogout);

        // final like CANCELLED: DELETE answers 409 and changes nothing
        try {
            ctx.cancel(untracked);
            ctx.fail("DELETE of a NO_DEVICE_REGISTERED notification answers 409", "it was accepted",
                    "spec 2.2: the state is final");
        } catch (ReliablePushException e) {
            ctx.check(e.status() == 409, "DELETE answers 409", () -> "HTTP " + e.status(), "spec 2.2");
            ctx.log("cancel answered 409");
        }

        // a device registered later does not reopen it; its DELIVERED for the old push is LATE
        SimulatedDevice later = ctx.register(SHOP, never, Platform.IOS);
        ctx.devices().receivePush(untracked, SHOP, never, Map.of(later.deviceId(), DeviceBehaviour.parse("deliver:0ms")));
        DeviceEventRecord e = ctx.awaitDeviceEvents(untracked, 1, QUICK).get(0);
        ctx.check("LATE".equals(e.outcome()), "the late DELIVERED stored as LATE", () -> e.outcome(),
                "spec 2.2: events for a NO_DEVICE_REGISTERED row are LATE (recorded, no effect)");
        Notification still = ctx.notification(untracked).orElseThrow();
        ctx.check(still.state() == NotificationState.NO_DEVICE_REGISTERED && still.deliveredAt() == null,
                "still NO_DEVICE_REGISTERED, not delivered", () -> still.state() + " deliveredAt=" + still.deliveredAt(),
                "a device registered later must not reopen the row");

        // contrast: with a device the same user's next push is tracked
        UUID tracked = ctx.send(SHOP, never, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(later.deviceId(), "deliver:500ms"));
        ctx.awaitState(tracked, NotificationState.DELIVERED, QUICK);

        ctx.expectNoWebhook(untracked, sent.plus(QUIET_AFTER_SEND));
        ctx.expectNoWebhook(afterLogout.trackingId(), Instant.now());
        ctx.awaitLogEntry("notification.no_device_registered", "rp.tracking_id", untracked.toString(), QUICK);
    }

    private static void expectNoDevice(ScenarioContext ctx, FakeSender.SendResult r) {
        ctx.check(r.registered(), "registration answered 201", () -> "failed with HTTP " + r.failureStatus(),
                "the api container log has the request");
        Notification n = r.notification();
        ctx.check(n.state() == NotificationState.NO_DEVICE_REGISTERED && n.dueAt() == null && n.closedAt() != null,
                "201 with state NO_DEVICE_REGISTERED, dueAt null, closedAt set",
                () -> n.state() + " dueAt=" + n.dueAt() + " closedAt=" + n.closedAt(),
                "spec 2.1: the user has no active device for the app");
    }
}
