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

    private static ReceivedWebhook hook(String app, UUID id, int attempt) {
        return new ReceivedWebhook(app, id, id == null ? null : id.toString(), attempt, "NOT_DELIVERED", true,
                Instant.now(), 200, Map.of());
    }

    @Test
    void boundedToLast10000DroppingOldest() {
        WebhookInbox inbox = new WebhookInbox();
        for (int i = 0; i < 10_005; i++) {
            inbox.record(hook("shop", UUID.randomUUID(), i));
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

        inbox.record(hook("shop", UUID.randomUUID(), 1));

        assertThat(seen).hasSize(1);
        assertThat(inbox.received("shop")).hasSize(1);
    }

    @Test
    void forTrackingAndClearResetsModes() {
        WebhookInbox inbox = new WebhookInbox();
        UUID id = UUID.randomUUID();
        inbox.record(hook("shop", id, 1));
        inbox.record(hook("shop", UUID.randomUUID(), 1));
        inbox.record(hook("shop", null, 0));
        inbox.setMode("shop", ReceiverMode.FAIL_500, Duration.ZERO);

        assertThat(inbox.forTracking(id)).hasSize(1);
        assertThat(inbox.mode("shop").mode()).isEqualTo(ReceiverMode.FAIL_500);

        inbox.clear();

        assertThat(inbox.received("shop")).isEmpty();
        assertThat(inbox.mode("shop").mode()).isEqualTo(ReceiverMode.OK);
    }
}
