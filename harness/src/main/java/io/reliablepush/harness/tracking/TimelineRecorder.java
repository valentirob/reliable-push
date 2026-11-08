package io.reliablepush.harness.tracking;

import io.reliablepush.harness.devices.DeviceSimulator;
import io.reliablepush.harness.receiver.ReceivedWebhook;
import io.reliablepush.harness.receiver.WebhookInbox;
import io.reliablepush.harness.sender.FakeSender;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Turns sender, device and webhook callbacks into timeline entries. The callbacks run on the caller's thread (receiver
 * request, device drain, sender) and only enqueue into a bounded queue (drop-oldest, counted); a single dispatcher
 * thread updates the {@link Timeline} and pushes to {@link ConsoleStream}. It never blocks the callers.
 */
@Component
public class TimelineRecorder {

    static final int QUEUE_CAPACITY = 10_000;
    private static final Logger LOG = LogManager.getLogger(TimelineRecorder.class);

    private final Timeline timeline;
    private final ConsoleStream stream;
    private final Clock clock;
    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private volatile Thread dispatcher;

    @Autowired
    public TimelineRecorder(Timeline timeline, ConsoleStream stream, FakeSender sender, WebhookInbox inbox,
                            DeviceSimulator devices) {
        this(timeline, stream, sender, inbox, devices, Clock.systemUTC());
    }

    TimelineRecorder(Timeline timeline, ConsoleStream stream, FakeSender sender, WebhookInbox inbox,
                     DeviceSimulator devices, Clock clock) {
        this.timeline = timeline;
        this.stream = stream;
        this.clock = clock;
        sender.onSent(s -> {
            enqueue(() -> {
                if (s.notification() != null) {
                    TimelineEntry reg = timeline.registered(s.appId(), s.notification());
                    if (reg != null) {
                        publish(s.trackingId(), reg);
                    }
                } else {
                    // the push still goes out (spec 1): the history shows why it is not tracked
                    Map<String, Object> d = new LinkedHashMap<>();
                    if (s.result().skipped()) {
                        d.put("reason", "skipped");
                    } else {
                        d.put("reason", "failed");
                        d.put("status", s.result().failureStatus());
                    }
                    publish(s.trackingId(), timeline.append(s.trackingId(), s.appId(),
                            new TimelineEntry(clock.instant(), "registration_missing", d)));
                }
            });
            enqueue(() -> {
                UUID id = s.trackingId();
                publish(id, timeline.append(id, s.appId(),
                        new TimelineEntry(clock.instant(), "push_simulated", Map.of("devices", s.deviceCount()))));
            });
        });
        devices.onEvent(e -> enqueue(() -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("deviceId", String.valueOf(e.deviceId()));
            d.put("type", e.type());
            d.put("outcome", e.outcome());
            publish(e.trackingId(), timeline.append(e.trackingId(), null,
                    new TimelineEntry(e.sentAt() != null ? e.sentAt() : clock.instant(), "event_sent", d)));
        }));
        inbox.onStarted(w -> enqueue(() -> webhook(w, "webhook_in_flight")));
        inbox.onReceived(w -> enqueue(() -> webhook(w, "webhook_received")));
    }

    @PostConstruct
    public void start() {
        Thread t = Thread.ofPlatform().daemon().name("timeline-recorder").unstarted(this::run);
        dispatcher = t;
        t.start();
    }

    @PreDestroy
    public void stop() {
        Thread t = dispatcher;
        if (t != null) {
            t.interrupt();
        }
    }

    public long dropped() {
        return dropped.get();
    }

    private void webhook(ReceivedWebhook w, String kind) {
        if (w.trackingId() != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("callId", String.valueOf(w.callId()));
            d.put("attempt", w.attempt());
            d.put("reason", w.reason());
            d.put("signatureValid", w.signatureValid());
            if (!w.isPending()) {
                d.put("status", w.respondedStatus());
            }
            TimelineEntry e = timeline.append(w.trackingId(), w.appId(), new TimelineEntry(
                    !w.isPending() && w.respondedAt() != null ? w.respondedAt() : w.receivedAt(), kind, d));
            stream.publishTimeline(w.trackingId(), e);
        }
        stream.publishWebhook(w);
    }

    private void publish(UUID id, TimelineEntry e) {
        stream.publishTimeline(id, e);
    }

    private void enqueue(Runnable r) {
        while (!queue.offer(r)) {
            if (queue.poll() != null) {
                dropped.incrementAndGet();
            }
        }
    }

    private void run() {
        try {
            while (true) {
                Runnable r = queue.take();
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOG.warn("timeline update failed: {}", e.getClass().getSimpleName());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
