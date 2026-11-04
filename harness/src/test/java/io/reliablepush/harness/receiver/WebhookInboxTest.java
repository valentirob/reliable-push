package io.reliablepush.harness.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookInboxTest {

    private static ReceivedWebhook pending(String app, UUID id, int attempt) {
        return new ReceivedWebhook(app, id, id == null ? null : id.toString(), attempt, "NOT_DELIVERED", true,
                Instant.now(), 0, Map.of(), UUID.randomUUID(), null);
    }

    private static ReceivedWebhook hook(WebhookInbox inbox, String app, UUID id, int attempt) {
        ReceivedWebhook p = pending(app, id, attempt);
        inbox.begin(p);
        inbox.complete(p.callId(), 200, Instant.now());
        return p;
    }

    @Test
    void boundedToLast10000DroppingOldest() {
        WebhookInbox inbox = new WebhookInbox();
        for (int i = 0; i < 10_005; i++) {
            hook(inbox, "shop", UUID.randomUUID(), i);
        }

        List<ReceivedWebhook> all = inbox.received("shop");
        assertThat(all).hasSize(10_000);
        assertThat(all.getFirst().attempt()).isEqualTo(5);
        assertThat(all.getLast().attempt()).isEqualTo(10_004);
    }

    @Test
    void throwingListenerDoesNotBreakRecordingOrOtherListeners() {
        WebhookInbox inbox = new WebhookInbox();
        List<ReceivedWebhook> seen = new ArrayList<>();
        inbox.onReceived(h -> { throw new IllegalStateException("boom"); });
        inbox.onReceived(seen::add);

        hook(inbox, "shop", UUID.randomUUID(), 1);

        assertThat(seen).hasSize(1);
        assertThat(inbox.received("shop")).hasSize(1);
    }

    @Test
    void eventOrderIsStartedThenReceivedAndInFlightTracksPending() {
        WebhookInbox inbox = new WebhookInbox();
        List<String> events = new ArrayList<>();
        inbox.onStarted(h -> events.add("started:" + h.respondedStatus()));
        inbox.onReceived(h -> events.add("received:" + h.respondedStatus()));
        ReceivedWebhook p = pending("shop", UUID.randomUUID(), 1);

        inbox.begin(p);
        assertThat(inbox.inFlight("shop")).isEqualTo(1);
        assertThat(inbox.inFlight()).hasSize(1);
        assertThat(inbox.inFlight("other")).isZero();
        inbox.complete(p.callId(), 500, Instant.now());

        assertThat(events).containsExactly("started:0", "received:500");
        assertThat(inbox.inFlight("shop")).isZero();
        assertThat(inbox.received("shop")).singleElement().satisfies(h -> {
            assertThat(h.isPending()).isFalse();
            assertThat(h.respondedAt()).isNotNull();
        });
    }

    @Test
    void completeAfterClearIsNoOp() {
        WebhookInbox inbox = new WebhookInbox();
        List<ReceivedWebhook> seen = new ArrayList<>();
        inbox.onReceived(seen::add);
        ReceivedWebhook p = pending("shop", UUID.randomUUID(), 1);
        inbox.begin(p);

        inbox.clear();
        inbox.complete(p.callId(), 200, Instant.now());

        assertThat(inbox.received("shop")).isEmpty();
        assertThat(seen).isEmpty();
    }

    @Test
    void forTrackingAndClearResetsModes() {
        WebhookInbox inbox = new WebhookInbox();
        UUID id = UUID.randomUUID();
        hook(inbox, "shop", id, 1);
        hook(inbox, "shop", UUID.randomUUID(), 1);
        hook(inbox, "shop", null, 0);
        inbox.setMode("shop", ReceiverMode.FAIL_500, Duration.ZERO);

        assertThat(inbox.forTracking(id)).hasSize(1);
        assertThat(inbox.mode("shop").mode()).isEqualTo(ReceiverMode.FAIL_500);

        inbox.clear();

        assertThat(inbox.received("shop")).isEmpty();
        assertThat(inbox.mode("shop").mode()).isEqualTo(ReceiverMode.OK);
    }
}
