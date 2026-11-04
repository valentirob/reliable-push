package io.reliablepush.harness.receiver;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/** In-memory record of every receiver call (last 10,000) plus the per-app failure mode. Thread-safe. */
@Component
public class WebhookInbox {

    static final int CAPACITY = 10_000;
    private static final Logger LOG = LogManager.getLogger(WebhookInbox.class);

    /** Current answer mode of an app; {@code hang} only matters for {@link ReceiverMode#HANG}. */
    public record Mode(ReceiverMode mode, Duration hang) {
        static final Mode DEFAULT = new Mode(ReceiverMode.OK, Duration.ZERO);
    }

    private final Object lock = new Object();
    private final ArrayDeque<ReceivedWebhook> entries = new ArrayDeque<>();
    private final Map<String, Mode> modes = new ConcurrentHashMap<>();
    private final List<Consumer<ReceivedWebhook>> listeners = new CopyOnWriteArrayList<>();

    public void setMode(String appId, ReceiverMode mode, Duration hang) {
        modes.put(appId, new Mode(mode, hang == null ? Duration.ZERO : hang));
    }

    public Mode mode(String appId) {
        return modes.getOrDefault(appId, Mode.DEFAULT);
    }

    public List<ReceivedWebhook> received(String appId) {
        synchronized (lock) {
            return entries.stream().filter(e -> e.appId().equals(appId)).toList();
        }
    }

    public List<ReceivedWebhook> forTracking(UUID id) {
        synchronized (lock) {
            return entries.stream().filter(e -> id.equals(e.trackingId())).toList();
        }
    }

    /** Drops all entries and resets every app to {@link ReceiverMode#OK}. */
    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
        modes.clear();
    }

    public void onReceived(Consumer<ReceivedWebhook> listener) {
        listeners.add(listener);
    }

    /** Records the call, then notifies listeners synchronously; a failing listener is logged and ignored. */
    void record(ReceivedWebhook webhook) {
        synchronized (lock) {
            entries.addLast(webhook);
            while (entries.size() > CAPACITY) {
                entries.removeFirst();
            }
        }
        for (Consumer<ReceivedWebhook> listener : listeners) {
            try {
                listener.accept(webhook);
            } catch (RuntimeException e) {
                LOG.warn("webhook listener failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
