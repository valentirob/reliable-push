package io.reliablepush.harness.receiver;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    private final LinkedHashMap<UUID, ReceivedWebhook> entries = new LinkedHashMap<>();
    private final Map<String, Mode> modes = new ConcurrentHashMap<>();
    private final List<Consumer<ReceivedWebhook>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ReceivedWebhook>> startListeners = new CopyOnWriteArrayList<>();

    public void setMode(String appId, ReceiverMode mode, Duration hang) {
        modes.put(appId, new Mode(mode, hang == null ? Duration.ZERO : hang));
    }

    public Mode mode(String appId) {
        return modes.getOrDefault(appId, Mode.DEFAULT);
    }

    public List<ReceivedWebhook> received(String appId) {
        synchronized (lock) {
            return entries.values().stream().filter(e -> e.appId().equals(appId)).toList();
        }
    }

    public List<ReceivedWebhook> forTracking(UUID id) {
        synchronized (lock) {
            return entries.values().stream().filter(e -> id.equals(e.trackingId())).toList();
        }
    }

    /** Number of calls of this app that arrived but have not been answered yet. */
    public int inFlight(String appId) {
        synchronized (lock) {
            return (int) entries.values().stream().filter(e -> e.isPending() && e.appId().equals(appId)).count();
        }
    }

    public List<ReceivedWebhook> inFlight() {
        synchronized (lock) {
            return entries.values().stream().filter(ReceivedWebhook::isPending).toList();
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

    /** Listener for calls that have arrived and are still pending (status 0). */
    public void onStarted(Consumer<ReceivedWebhook> listener) {
        startListeners.add(listener);
    }

    /** Appends a pending call (status 0) at arrival, then notifies the onStarted listeners. */
    void begin(ReceivedWebhook pending) {
        synchronized (lock) {
            entries.put(pending.callId(), pending);
            while (entries.size() > CAPACITY) {
                var eldest = entries.entrySet().iterator();
                eldest.next();
                eldest.remove();
            }
        }
        notify(startListeners, pending);
    }

    /**
     * Sets the final status of a pending call and notifies the onReceived listeners. No-op (no event) when the entry
     * was trimmed or cleared in the meantime.
     */
    void complete(UUID callId, int status, Instant respondedAt) {
        ReceivedWebhook done;
        synchronized (lock) {
            ReceivedWebhook pending = entries.get(callId);
            if (pending == null) {
                return;
            }
            done = new ReceivedWebhook(pending.appId(), pending.trackingId(), pending.idempotencyKey(),
                    pending.attempt(), pending.reason(), pending.signatureValid(), pending.receivedAt(), status,
                    pending.body(), callId, respondedAt);
            entries.put(callId, done);
        }
        notify(listeners, done);
    }

    private static void notify(List<Consumer<ReceivedWebhook>> targets, ReceivedWebhook webhook) {
        for (Consumer<ReceivedWebhook> listener : targets) {
            try {
                listener.accept(webhook);
            } catch (RuntimeException e) {
                LOG.warn("webhook listener failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
