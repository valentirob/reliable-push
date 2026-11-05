package io.reliablepush.harness.tracking;

import io.reliablepush.client.Notification;
import io.reliablepush.client.NotificationState;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Per-notification history plus the latest known {@link Notification}. Bounded to the last 2,000 notifications (LRU by
 * last update). Thread-safe; every method is short and does no I/O.
 */
@Component
public class Timeline {

    public static final int CAPACITY = 2_000;

    private static final class Holder {
        String appId;
        Notification current;
        boolean registered;
        final List<TimelineEntry> entries = new ArrayList<>();
    }

    private final int capacity;
    private final Clock clock;
    /** Iteration order = least recently updated first. */
    private final LinkedHashMap<UUID, Holder> items = new LinkedHashMap<>();

    public Timeline() {
        this(CAPACITY, Clock.systemUTC());
    }

    Timeline(int capacity, Clock clock) {
        this.capacity = capacity;
        this.clock = clock;
    }

    /** Appends an entry (kept ordered by time, stable for equal times). {@code appId} may be null when unknown. */
    public synchronized TimelineEntry append(UUID trackingId, String appId, TimelineEntry entry) {
        Holder h = touch(trackingId);
        if (appId != null) {
            h.appId = appId;
        }
        insert(h, entry);
        return entry;
    }

    /** The sender just registered this notification; returns the entry, or null when the history already has one. */
    public synchronized TimelineEntry registered(String appId, Notification n) {
        Holder h = touch(n.trackingId());
        if (h.registered) {
            return null; // the backend (poller) got there first: never regress its newer snapshot
        }
        h.appId = appId;
        h.current = n;
        h.registered = true;
        TimelineEntry e = new TimelineEntry(clock.instant(), "registered",
                Map.of("source", "sender", "state", n.state().name()));
        insert(h, e);
        return e;
    }

    /**
     * Folds a notification seen on the backend into the history. A notification never seen before gets a
     * {@code registered} entry {source: backend} (this is how a restarted harness rebuilds itself, without a storm of
     * "from null" transitions); a known one gets {@code state_changed} (and {@code cancelled}) on a transition and
     * nothing otherwise. Unchanged observations do not refresh the LRU position.
     */
    public synchronized List<TimelineEntry> observe(String appId, Notification n) {
        Holder h = items.get(n.trackingId());
        List<TimelineEntry> out = new ArrayList<>(2);
        if (h == null || h.current == null && !h.registered) {
            h = touch(n.trackingId());
            h.appId = appId;
            h.current = n;
            h.registered = true;
            out.add(new TimelineEntry(n.createdAt() != null ? n.createdAt() : clock.instant(), "registered",
                    Map.of("source", "backend", "state", n.state().name())));
        } else {
            if (h.current != null && h.current.state() != n.state()) {
                h = touch(n.trackingId());
                Instant at = notBeforeLast(h, clock.instant());
                out.add(new TimelineEntry(at, "state_changed",
                        Map.of("from", h.current.state().name(), "to", n.state().name())));
                if (n.state() == NotificationState.CANCELLED) {
                    out.add(new TimelineEntry(at, "cancelled", Map.of()));
                }
            }
            h.appId = appId;
            h.current = n;
        }
        out.forEach(e -> insert(items.get(n.trackingId()), e));
        return out;
    }

    public synchronized List<TimelineEntry> entries(UUID trackingId) {
        Holder h = items.get(trackingId);
        return h == null ? List.of() : List.copyOf(h.entries);
    }

    /** The app a notification belongs to, when known. */
    public synchronized Optional<String> appId(UUID trackingId) {
        Holder h = items.get(trackingId);
        return h == null ? Optional.empty() : Optional.ofNullable(h.appId);
    }

    public synchronized Optional<Notification> current(UUID trackingId) {
        Holder h = items.get(trackingId);
        return h == null ? Optional.empty() : Optional.ofNullable(h.current);
    }

    /** Latest known notifications of an app, newest first. */
    public synchronized List<Notification> recent(String appId, int limit) {
        return items.values().stream()
                .filter(h -> h.current != null && appId.equals(h.appId))
                .map(h -> h.current)
                .sorted(Comparator.comparing(Notification::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(limit, 0))
                .toList();
    }

    public synchronized int size() {
        return items.size();
    }

    private Holder touch(UUID id) {
        Holder h = items.remove(id);
        if (h == null) {
            h = new Holder();
        }
        items.put(id, h);
        while (items.size() > capacity) {
            var eldest = items.entrySet().iterator();
            eldest.next();
            eldest.remove();
        }
        return h;
    }

    /** Clamps clock skew (backend creation times vs this clock) so a transition never sorts before its history. */
    private static Instant notBeforeLast(Holder h, Instant at) {
        if (h.entries.isEmpty()) {
            return at;
        }
        Instant last = h.entries.get(h.entries.size() - 1).at();
        return last.isAfter(at) ? last : at;
    }

    private static void insert(Holder h, TimelineEntry e) {
        int i = h.entries.size();
        while (i > 0 && h.entries.get(i - 1).at().isAfter(e.at())) {
            i--;
        }
        h.entries.add(i, e);
    }
}
