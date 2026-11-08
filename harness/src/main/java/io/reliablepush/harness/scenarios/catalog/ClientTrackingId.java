package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.Notification;
import io.reliablepush.client.NotificationState;
import io.reliablepush.client.RegisterRequest;
import io.reliablepush.client.ReliablePushException;
import io.reliablepush.client.SenderClient;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.SimulatedDevice;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.scenarios.ScenarioContext;
import io.reliablepush.harness.sender.FakeSender;
import io.reliablepush.payload.TrackingIds;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Spec 2.1 / FR-24: the sender generates the tracking id (payload jar) and may retry the registration with it, for
 * example after a lost response. Every retry answers 201 with the same row (first registration wins), the push is sent
 * once with that id, and the unmet policy gives exactly one fallback. A different id with the same externalId is 409.
 */
@Component
@Order(20)
class ClientTrackingId extends CatalogScenario {

    ClientTrackingId() {
        super("client-tracking-id",
                "The sender's own tracking id (TrackingIds.newId): two retries return the same "
                        + "row, the push uses the id, the device never reports: exactly one webhook; another id with "
                        + "the same externalId is 409.");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        String user = ctx.userId(1);
        SimulatedDevice d = ctx.register(SHOP, user, Platform.IOS);
        String tid = TrackingIds.newId();
        UUID id = UUID.fromString(tid);
        String externalId = "order-" + ctx.userIdPrefix() + "-" + UUID.randomUUID();
        SenderClient api = ctx.sender().senderClient(SHOP);

        // first attempt: its response is "lost" by the sender, which retries with the same id
        Notification first = api.register(request(ctx, tid, user, externalId));
        ctx.track(id, SHOP);
        ctx.log("registered " + id + " (first attempt) state=" + first.state());
        ctx.check(first.trackingId().equals(id) && first.state() == NotificationState.PENDING,
                "201 PENDING with trackingId " + id, () -> first.trackingId() + " " + first.state(),
                "the backend must store the sender's id");
        Notification retry = api.register(request(ctx, tid, user, externalId));
        ctx.check(retry.trackingId().equals(id) && retry.createdAt().equals(first.createdAt())
                        && retry.state() == first.state(),
                "the retry returns the same row (createdAt " + first.createdAt() + ")",
                () -> retry.trackingId() + " createdAt " + retry.createdAt() + " " + retry.state(),
                "spec 2.1: idempotent on trackingId (insert ... on conflict do nothing, then a re-read)");

        // the sender flow: register (a third time, same id) and push with it
        FakeSender.SendResult sent = ctx.send(SHOP, user, FallbackPolicy.NOT_DELIVERED, TIMEOUT_MINUTES,
                Map.of(d.deviceId(), "never"), id, externalId, false);
        ctx.check(sent.registered() && sent.notification().createdAt().equals(first.createdAt()),
                "the sender's registration returns the same row", () -> sent.registered()
                        ? "createdAt " + sent.notification().createdAt() : "failed with HTTP " + sent.failureStatus(),
                "follow rp.tracking_id=" + id + " in harness/build/e2e/logs/");

        // another id with the same externalId: 409, nothing stored
        String other = TrackingIds.newId();
        try {
            api.register(request(ctx, other, user, externalId));
            ctx.fail("409 for a second trackingId with externalId " + externalId, "201",
                    "spec 2.1: an externalId belongs to one trackingId");
        } catch (ReliablePushException e) {
            ctx.check(e.status() == 409, "409 for a second trackingId with the same externalId",
                    () -> "HTTP " + e.status(), "spec 2.1");
        }
        ctx.check(api.get(UUID.fromString(other)).isEmpty(), "no row for " + other, () -> "a row exists",
                "the conflicting registration must not be stored");

        ctx.awaitState(id, NotificationState.FALLBACK_TRIGGERED, FALLBACK_WAIT);
        ctx.pause(SETTLE);
        ReceivedWebhook w = ctx.expectWebhookCount(id, 1).get(0);
        ctx.check(id.toString().equals(w.idempotencyKey()), "Idempotency-Key = " + id,
                () -> "Idempotency-Key = " + w.idempotencyKey(), "spec 5: Idempotency-Key is the trackingId");
    }

    private static RegisterRequest request(ScenarioContext ctx, String trackingId, String user, String externalId) {
        return RegisterRequest.builder().trackingId(trackingId).userId(user).externalId(externalId)
                .policy(FallbackPolicy.NOT_DELIVERED).timeoutMinutes(TIMEOUT_MINUTES).fallback(ctx.newFallback())
                .build();
    }
}
