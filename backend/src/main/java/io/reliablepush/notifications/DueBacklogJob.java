package io.reliablepush.notifications;

import io.reliablepush.shared.metrics.RpMetrics;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the {@code rp_due_backlog} gauge (spec 9.2) current: every 30 s it counts the rows the fallback worker could
 * claim right now and caches the number in {@link RpMetrics}; a Prometheus scrape reads the cache and never queries.
 * Runs in the worker role only, which is also the only role that exposes the gauge.
 */
@Component
@Profile("worker")
public class DueBacklogJob {

    private static final Logger LOG = LogManager.getLogger(DueBacklogJob.class);

    private final NotificationRepository notifications;
    private final Clock clock;
    private final RpMetrics metrics;

    DueBacklogJob(NotificationRepository notifications, Clock clock, RpMetrics metrics) {
        this.notifications = notifications;
        this.clock = clock;
        this.metrics = metrics;
        metrics.registerDueBacklogGauge();
    }

    /** First run right after startup (the gauge is 0 until then), then every 30 s after the previous run. */
    @Scheduled(
            fixedDelayString = "${reliable-push.worker.backlog-refresh-interval:30s}",
            initialDelay = 0)
    public void scheduled() {
        try {
            refresh();
        } catch (RuntimeException e) {
            // the previous value stays; the next run tries again
            LOG.warn("Due backlog refresh failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return the number of due rows now, also published to the gauge */
    public long refresh() {
        long due = notifications.countDue(clock.instant().truncatedTo(ChronoUnit.MICROS));
        metrics.dueBacklog(due);
        return due;
    }
}
