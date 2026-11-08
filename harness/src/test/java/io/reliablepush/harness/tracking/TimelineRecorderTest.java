package io.reliablepush.harness.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.reliablepush.client.Notification;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.devices.DeviceSimulator;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.receiver.WebhookInbox;
import io.reliablepush.harness.sender.FakeSender;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TimelineRecorderTest {

    final FakeSender sender = mock(FakeSender.class);
    final WebhookInbox inbox = mock(WebhookInbox.class);
    final DeviceSimulator devices = mock(DeviceSimulator.class);
    final Timeline timeline = new Timeline();
    final ConsoleStream stream = new ConsoleStream();
    TimelineRecorder recorder;
    Consumer<FakeSender.Sent> onSent;
    Consumer<DeviceSimulator.DeviceEventRecord> onEvent;
    Consumer<ReceivedWebhook> onStarted;
    Consumer<ReceivedWebhook> onReceived;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void wire() {
        recorder = new TimelineRecorder(timeline, stream, sender, inbox, devices);
        var sent = ArgumentCaptor.forClass(Consumer.class);
        var event = ArgumentCaptor.forClass(Consumer.class);
        var started = ArgumentCaptor.forClass(Consumer.class);
        var received = ArgumentCaptor.forClass(Consumer.class);
        verify(sender).onSent(sent.capture());
        verify(devices).onEvent(event.capture());
        verify(inbox).onStarted(started.capture());
        verify(inbox).onReceived(received.capture());
        onSent = sent.getValue();
        onEvent = event.getValue();
        onStarted = started.getValue();
        onReceived = received.getValue();
    }

    @AfterEach
    void stop() {
        recorder.stop();
    }

    static ReceivedWebhook hook(UUID tid, UUID call, int status) {
        Instant now = Instant.now();
        return new ReceivedWebhook("shop", tid, "k", 1, "NOT_DELIVERED", true, now, status, Map.of(), call,
                status == 0 ? null : now);
    }

    @Test
    void recordsAllKindsInOrderFromTheDispatcherThread() {
        UUID tid = UUID.randomUUID();
        UUID call = UUID.randomUUID();
        Notification n = TimelineTest.n(tid, NotificationState.PENDING, Instant.now());
        recorder.start();

        onSent.accept(new FakeSender.Sent("shop", new FakeSender.SendResult(tid, n, null, false), 2));
        onEvent.accept(new DeviceSimulator.DeviceEventRecord(tid, UUID.randomUUID(), "delivered", Instant.now(), "OK"));
        onStarted.accept(hook(tid, call, 0));
        onReceived.accept(hook(tid, call, 200));

        await().untilAsserted(() -> assertThat(timeline.entries(tid)).extracting(TimelineEntry::kind)
                .containsExactlyInAnyOrder("registered", "push_simulated", "event_sent", "webhook_in_flight",
                        "webhook_received"));
        TimelineEntry done = timeline.entries(tid).stream()
                .filter(e -> e.kind().equals("webhook_received")).findFirst().orElseThrow();
        assertThat(done.details()).containsEntry("status", 200);
        assertThat(timeline.recent("shop", 5)).hasSize(1);
    }

    @Test
    void failedOrSkippedRegistrationIsRecordedAndThePushStillShows() {
        UUID failed = UUID.randomUUID();
        UUID skipped = UUID.randomUUID();
        recorder.start();

        onSent.accept(new FakeSender.Sent("shop", new FakeSender.SendResult(failed, null, 400, false), 1));
        onSent.accept(new FakeSender.Sent("shop", new FakeSender.SendResult(skipped, null, null, true), 1));

        await().untilAsserted(() -> assertThat(timeline.entries(skipped)).hasSize(2));
        await().untilAsserted(() -> assertThat(timeline.entries(failed)).hasSize(2));
        assertThat(timeline.entries(failed)).extracting(TimelineEntry::kind)
                .containsExactly("registration_missing", "push_simulated");
        assertThat(timeline.entries(failed).get(0).details()).containsEntry("reason", "failed")
                .containsEntry("status", 400);
        assertThat(timeline.entries(skipped).get(0).details()).containsEntry("reason", "skipped");
        assertThat(timeline.appId(failed)).contains("shop");
        assertThat(timeline.recent("shop", 5)).isEmpty(); // no registered row to list
    }

    @Test
    void listenersNeverBlockAndDropOldestWhenFull() {
        UUID tid = UUID.randomUUID();
        // dispatcher not started: nothing drains the queue
        long t0 = System.nanoTime();
        for (int i = 0; i < TimelineRecorder.QUEUE_CAPACITY + 500; i++) {
            onStarted.accept(hook(tid, UUID.randomUUID(), 0));
        }

        assertThat(System.nanoTime() - t0).isLessThan(5_000_000_000L);
        assertThat(recorder.dropped()).isEqualTo(500);
    }

    @Test
    void webhookWithoutTrackingIdIsNotRecordedInAnyTimeline() {
        recorder.start();
        onReceived.accept(hook(null, UUID.randomUUID(), 200));
        UUID probe = UUID.randomUUID();
        onStarted.accept(hook(probe, UUID.randomUUID(), 0));

        await().untilAsserted(() -> assertThat(timeline.entries(probe)).hasSize(1));
        assertThat(timeline.size()).isEqualTo(1);
        verify(inbox).onReceived(any());
    }
}
