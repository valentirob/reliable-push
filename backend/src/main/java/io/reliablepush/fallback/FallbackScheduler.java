package io.reliablepush.fallback;

import io.reliablepush.notifications.ClaimedNotification;
import io.reliablepush.notifications.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The fallback worker loop (spec 2). Every poll interval it claims up to {@code batch-size} due rows (atomically,
 * {@code SKIP LOCKED}: replicas never claim the same row) with a lease, then dispatches them.
 *
 * <p>Concurrency: the claimed rows are processed in parallel, one virtual thread per row (so at most
 * {@code batch-size}), and the tick returns when all are done. Sequential processing would let a batch of slow
 * webhooks (up to {@code http-timeout} each) outlast the lease of the rows at its end, which another replica would then
 * re-claim and re-send; in parallel a tick takes about one {@code http-timeout} at most, well inside the lease.
 */
public class FallbackScheduler {

    private final NotificationRepository notifications;
    private final FallbackDispatcher dispatcher;
    private final Clock clock;
    private final Duration lease;
    private final int batchSize;

    FallbackScheduler(
            NotificationRepository notifications,
            FallbackDispatcher dispatcher,
            Clock clock,
            Duration lease,
            int batchSize) {
        this.notifications = notifications;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.lease = lease;
        this.batchSize = batchSize;
    }

    /**
     * One poll: claim, dispatch, wait for all claimed rows. The first scheduled run is one interval after startup.
     *
     * @return the number of rows claimed
     */
    @Scheduled(
            fixedDelayString = "${reliable-push.worker.poll-interval:5s}",
            initialDelayString = "${reliable-push.worker.poll-interval:5s}")
    public int tick() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<ClaimedNotification> claimed = notifications.claimDue(now, now.plus(lease), batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }
        try (ExecutorService rows = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ClaimedNotification row : claimed) {
                rows.execute(() -> dispatcher.process(row));
            }
        }
        return claimed.size();
    }
}
