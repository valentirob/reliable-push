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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The fallback worker loop (spec 2). Every poll interval one tick claims due rows (atomically, {@code SKIP LOCKED}:
 * replicas never claim the same row) with a lease, and dispatches them.
 *
 * <p>Throughput: a tick does not stop after one claim. It keeps claiming while the previous claim came back full (more
 * rows are probably due), for at most half the lease, so a large backlog drains at the speed of the receivers instead
 * of one {@code batch-size} per poll interval. Each claim takes {@code min(batch-size, free slots)} rows, where at most
 * {@code max-concurrency} webhook calls are in flight: a row is only claimed when it can start at once, so no lease
 * runs out while a claimed row waits for a slot, and a new claim starts as soon as any call finishes rather than after
 * the slowest of a batch. Every claim uses a fresh {@code now} for its due check and lease.
 *
 * <p>Concurrency: one virtual thread per row; the tick returns when all its rows are done, and the scheduler runs ticks
 * with a fixed delay, so ticks of one instance never overlap.
 *
 * <p>Graceful stop: {@link #requestStop()} sets a flag that is checked before every claim, the first included, so a
 * tick claims nothing more but still waits for its rows in flight; {@link #awaitIdle} waits for a running tick to
 * return (used by {@link FallbackWorkerLifecycle} on shutdown, before the connection pool closes).
 */
public class FallbackScheduler {

    private final NotificationRepository notifications;
    private final FallbackDispatcher dispatcher;
    private final Clock clock;
    private final Duration lease;
    private final int batchSize;
    private final int maxConcurrency;

    private volatile boolean stopRequested;
    private final AtomicInteger rowsInFlight = new AtomicInteger();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private boolean ticking; // guarded by lock

    FallbackScheduler(
            NotificationRepository notifications,
            FallbackDispatcher dispatcher,
            Clock clock,
            Duration lease,
            int batchSize,
            int maxConcurrency) {
        if (batchSize < 1 || maxConcurrency < 1) {
            throw new IllegalArgumentException("batch-size and max-concurrency must be at least 1");
        }
        this.notifications = notifications;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.lease = lease;
        this.batchSize = batchSize;
        this.maxConcurrency = maxConcurrency;
    }

    /**
     * One poll: claim and dispatch until the due rows are exhausted or half the lease has passed, then wait for every
     * claimed row. The first scheduled run is one interval after startup.
     *
     * @return the number of rows claimed
     */
    @Scheduled(
            fixedDelayString = "${reliable-push.worker.poll-interval:5s}",
            initialDelayString = "${reliable-push.worker.poll-interval:5s}")
    public int tick() {
        lock.lock();
        try {
            ticking = true;
        } finally {
            lock.unlock();
        }
        try {
            return claimAndDispatch();
        } finally {
            lock.lock();
            try {
                ticking = false;
                idle.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * After this call no tick claims another row; a tick already running returns once the rows it dispatched are done.
     * Irreversible: the instance is shutting down.
     */
    public void requestStop() {
        stopRequested = true;
    }

    boolean isStopRequested() {
        return stopRequested;
    }

    /** The rows of the running tick whose dispatch has not finished yet. */
    int rowsInFlight() {
        return rowsInFlight.get();
    }

    /**
     * Waits until no tick is running.
     *
     * @return {@code true} if idle, {@code false} if a tick was still running after {@code timeout} (or the thread was
     *     interrupted)
     */
    boolean awaitIdle(Duration timeout) {
        long nanos = timeout.toNanos();
        lock.lock();
        try {
            while (ticking) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = idle.awaitNanos(nanos);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    private int claimAndDispatch() {
        Instant claimUntil = now().plus(lease.dividedBy(2));
        Semaphore slots = new Semaphore(maxConcurrency);
        int total = 0;
        try (ExecutorService rows = Executors.newVirtualThreadPerTaskExecutor()) {
            boolean first = true;
            while (!stopRequested) {
                int capacity = reserve(slots);
                if (capacity == 0) {
                    break; // interrupted (shutdown): dispatch nothing more, wait for the rows in flight
                }
                if (stopRequested) { // stop requested while waiting for a free slot: claim nothing
                    slots.release(capacity);
                    break;
                }
                Instant now = now();
                if (!first && !now.isBefore(claimUntil)) {
                    slots.release(capacity);
                    break;
                }
                first = false;
                List<ClaimedNotification> claimed;
                try {
                    claimed = notifications.claimDue(now, now.plus(lease), capacity);
                } catch (RuntimeException e) {
                    slots.release(capacity);
                    throw e;
                }
                slots.release(capacity - claimed.size());
                rowsInFlight.addAndGet(claimed.size());
                for (ClaimedNotification row : claimed) {
                    rows.execute(() -> {
                        try {
                            dispatcher.process(row);
                        } finally {
                            rowsInFlight.decrementAndGet();
                            slots.release();
                        }
                    });
                }
                total += claimed.size();
                if (claimed.size() < capacity) {
                    break; // fewer due rows than asked for: the backlog is drained
                }
            }
        }
        return total;
    }

    /**
     * Waits for at least one free slot, then takes every free slot up to {@code batch-size}.
     *
     * @return the number of slots taken, 0 if interrupted
     */
    private int reserve(Semaphore slots) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        int taken = 1 + slots.drainPermits();
        if (taken > batchSize) {
            slots.release(taken - batchSize);
            taken = batchSize;
        }
        return taken;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
