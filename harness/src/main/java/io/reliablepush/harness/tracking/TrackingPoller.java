package io.reliablepush.harness.tracking;

import io.reliablepush.client.Notification;
import io.reliablepush.client.NotificationPage;
import io.reliablepush.client.SenderClient;
import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.sender.FakeSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The only component that polls the backend for notification state (one poll per app per interval, regardless of
 * console tabs). Looks back one hour, up to 5 pages of 100, and folds the result into the {@link Timeline}; the first
 * poll after a start thereby rebuilds the history from the backend. Backend errors keep the previous state and are
 * logged at WARN at most once per app per minute.
 */
@Component
public class TrackingPoller {

    static final Duration LOOKBACK = Duration.ofHours(1);
    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 5;
    static final Duration WARN_EVERY = Duration.ofMinutes(1);
    private static final Logger LOG = LogManager.getLogger(TrackingPoller.class);

    private final HarnessProperties properties;
    private final FakeSender sender;
    private final Timeline timeline;
    private final ConsoleStream stream;
    private final Clock clock;
    private final Map<String, Instant> lastWarn = new ConcurrentHashMap<>();

    @Autowired
    public TrackingPoller(HarnessProperties properties, FakeSender sender, Timeline timeline, ConsoleStream stream) {
        this(properties, sender, timeline, stream, Clock.systemUTC());
    }

    TrackingPoller(HarnessProperties properties, FakeSender sender, Timeline timeline, ConsoleStream stream,
                   Clock clock) {
        this.properties = properties;
        this.sender = sender;
        this.timeline = timeline;
        this.stream = stream;
        this.clock = clock;
    }

    /** Interval from {@code harness.poll-interval} (default 1s); the first poll runs one interval after start. */
    @Scheduled(fixedDelayString = "${harness.poll-interval:1s}", initialDelayString = "${harness.poll-interval:1s}")
    public void poll() {
        for (String appId : properties.apps().keySet()) {
            try {
                pollApp(appId);
            } catch (RuntimeException e) {
                warn(appId, e);
            }
        }
    }

    void pollApp(String appId) {
        SenderClient client = sender.senderClient(appId);
        Instant since = clock.instant().minus(LOOKBACK);
        List<Notification> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            NotificationPage p = client.list(null, null, since, PAGE_SIZE, cursor);
            all.addAll(p.items());
            cursor = p.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        // oldest first so the history reads in creation order
        for (int i = all.size() - 1; i >= 0; i--) {
            Notification n = all.get(i);
            for (TimelineEntry e : timeline.observe(appId, n)) {
                stream.publishTimeline(n.trackingId(), e);
            }
        }
    }

    private void warn(String appId, RuntimeException e) {
        Instant now = clock.instant();
        Instant last = lastWarn.get(appId);
        if (last == null || Duration.between(last, now).compareTo(WARN_EVERY) >= 0) {
            lastWarn.put(appId, now);
            LOG.warn("backend poll failed for app {}: {}", appId, e.getClass().getSimpleName());
        }
    }
}
