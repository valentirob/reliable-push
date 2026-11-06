package io.reliablepush.notifications;

import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.shared.logging.EventLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily retention job (spec 3): deletes closed notifications older than {@code retention-days}; their events go
 * with them (ON DELETE CASCADE). A row that is not closed ({@code closed_at} null: still waiting for or being worked
 * on by the fallback worker) is never deleted, whatever its age. Runs in the worker role only.
 *
 * <p>The delete runs in chunks of {@value #CHUNK} rows, each its own short transaction, so a large backlog never holds
 * one long transaction or lock. Two replicas purging at the same time are harmless: a row is deleted once and the
 * second delete simply finds fewer rows.
 */
@Component
@Profile("worker")
public class RetentionJob {

    static final int CHUNK = 1_000;

    private final NotificationRepository notifications;
    private final Clock clock;
    private final Duration retention;
    private final ObjectProvider<RetentionGuard> guard;

    RetentionJob(NotificationRepository notifications, Clock clock, ReliablePushProperties properties,
            ObjectProvider<RetentionGuard> guard) {
        this.notifications = notifications;
        this.clock = clock;
        this.retention = Duration.ofDays(properties.retentionDays());
        this.guard = guard;
    }

    /**
     * Daily at 03:17 UTC by default (off the hour, away from other jobs' peaks). The Spring cron expression is
     * {@code reliable-push.retention.cron}, always in UTC; {@code -} disables the schedule (the test profile does).
     */
    @Scheduled(cron = "${reliable-push.retention.cron:0 17 3 * * *}", zone = "UTC")
    public void scheduled() {
        purge();
    }

    /**
     * Deletes every closed notification whose {@code closed_at} is older than the retention and logs
     * {@code retention.purged} with the total count (also when it is 0).
     *
     * @return the number of notifications deleted (0 when the {@link RetentionGuard} vetoed the run)
     */
    public int purge() {
        RetentionGuard veto = guard.getIfAvailable();
        if (veto != null && !veto.allowPurge()) {
            EventLog.warn("retention.skipped", Map.of("reason", "rollup_missing"));
            return 0;
        }
        Instant cutoff = clock.instant().minus(retention);
        int total = 0;
        int deleted;
        do {
            deleted = notifications.purgeClosedBefore(cutoff, CHUNK);
            total += deleted;
        } while (deleted >= CHUNK);
        EventLog.info("retention.purged", Map.of("count", total));
        return total;
    }
}
