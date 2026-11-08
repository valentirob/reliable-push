package io.reliablepush.fallback;

import io.reliablepush.fallback.circuit.Circuit;
import io.reliablepush.fallback.circuit.CircuitBreaker;
import io.reliablepush.fallback.circuit.Suppressor;
import io.reliablepush.notifications.ClaimedNotification;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.metrics.RpMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The fallback worker loop (spec 2). Every poll interval one tick first evaluates the fallback circuit of every
 * configured app and suppresses the apps that must not fire ({@link CircuitBreaker}, {@link Suppressor}; circuit
 * breaker spec 4 and 5), then claims due rows of the other apps (atomically, {@code SKIP LOCKED}: replicas never claim
 * the same row) with a lease, and dispatches them. A drain that lasts longer than one poll interval checks the
 * circuits again before its next claim. If a check fails, the tick logs WARN {@code fallback.circuit_check_failed}
 * (stack trace at ERROR from this class's logger), counts it in {@code rp_fallback_circuit_check_failed_total} and
 * claims nothing more (fail closed).
 *
 * <p>Throughput: a tick does not stop after one claim. It keeps claiming while the previous claim came back full (more
 * rows are probably due), for at most half the lease, so a large backlog drains at the speed of the receivers instead
 * of one {@code batch-size} per poll interval. Each claim takes {@code min(batch-size, free slots)} rows, where at most
 * {@code max-concurrency} webhook calls are in flight: a row is only claimed when it can start at once, so no lease
 * runs out while a claimed row waits for a slot, and a new claim starts as soon as any call finishes rather than after
 * the slowest of a batch. Every claim takes only the rows due at the instant of the last circuit check (its due
 * cut-off), never a row that came due after the circuits were evaluated, and leases them from a fresh {@code now};
 * the re-check of a long drain advances the cut-off.
 *
 * <p>Concurrency: one virtual thread per row; the tick returns when all its rows are done, and the scheduler runs ticks
 * with a fixed delay, so ticks of one instance never overlap.
 *
 * <p>Graceful stop: {@link #requestStop()} sets a flag that is checked before every claim, the first included, so a
 * tick claims nothing more but still waits for its rows in flight; {@link #awaitIdle} waits for a running tick to
 * return (used by {@link FallbackWorkerLifecycle} on shutdown, before the connection pool closes).
 */
public class FallbackScheduler {

    private static final Logger LOG = LogManager.getLogger(FallbackScheduler.class);

    private final NotificationRepository notifications;
    private final FallbackDispatcher dispatcher;
    private final CircuitBreaker breaker;
    private final Suppressor suppressor;
    private final RpMetrics metrics;
    private final List<String> appIds;
    private final boolean suspended;
    private final Clock clock;
    private final Duration lease;
    private final Duration pollInterval;
    private final int batchSize;
    private final int maxConcurrency;

    private volatile boolean stopRequested;
    private final AtomicInteger rowsInFlight = new AtomicInteger();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private int ticksRunning; // guarded by lock; a count, so awaitIdle stays correct even if ticks overlap

    FallbackScheduler(
            NotificationRepository notifications,
            FallbackDispatcher dispatcher,
            CircuitBreaker breaker,
            Suppressor suppressor,
            RpMetrics metrics,
            List<String> appIds,
            boolean suspended,
            Clock clock,
            Duration lease,
            Duration pollInterval,
            int batchSize,
            int maxConcurrency) {
        if (batchSize < 1 || maxConcurrency < 1) {
            throw new IllegalArgumentException("batch-size and max-concurrency must be at least 1");
        }
        this.notifications = notifications;
        this.dispatcher = dispatcher;
        this.breaker = breaker;
        this.suppressor = suppressor;
        this.metrics = metrics;
        metrics.registerFallbackCircuitCheckFailed();
        this.appIds = List.copyOf(appIds);
        this.suspended = suspended;
        this.clock = clock;
        this.lease = lease;
        this.pollInterval = pollInterval;
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
            ticksRunning++;
        } finally {
            lock.unlock();
        }
        try {
            return claimAndDispatch();
        } finally {
            lock.lock();
            try {
                if (--ticksRunning == 0) {
                    idle.signalAll();
                }
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
     * Waits until no tick is running (every running tick has returned, should ticks ever overlap).
     *
     * @return {@code true} if idle, {@code false} if a tick was still running after {@code timeout} (or the thread was
     *     interrupted)
     */
    boolean awaitIdle(Duration timeout) {
        long nanos = timeout.toNanos();
        lock.lock();
        try {
            while (ticksRunning > 0) {
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

    /**
     * Evaluates the circuits of the configured apps and suppresses the ones that must not fire (circuit breaker spec 4
     * and 5).
     *
     * @return the apps the following claims skip, or empty when the check failed: then nothing more is claimed (fail
     *     closed: a missed fallback is preferred to an unwanted one; the rows are claimed by a later tick)
     */
    private Optional<Set<String>> suppressedApps(Instant now) {
        try {
            Map<String, Circuit> circuits = breaker.evaluate(appIds, now);
            return Optional.of(suppressor.suppress(circuits, suspended, now));
        } catch (RuntimeException e) {
            metrics.fallbackCircuitCheckFailed();
            EventLog.warn("fallback.circuit_check_failed", Map.of("reason", e.getClass().getSimpleName()));
            LOG.error("Fallback circuit check failed; nothing is claimed until a check succeeds", e);
            return Optional.empty();
        }
    }

    private int claimAndDispatch() {
        if (stopRequested) {
            return 0;
        }
        Instant checkedAt = now();
        Optional<Set<String>> check = suppressedApps(checkedAt);
        if (check.isEmpty()) {
            return 0;
        }
        Set<String> excluded = check.get();
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
                if (!first && Duration.between(checkedAt, now).compareTo(pollInterval) >= 0) {
                    // a long drain: a storm coming due meanwhile is caught within one poll interval
                    Optional<Set<String>> again = suppressedApps(now);
                    if (again.isEmpty()) {
                        slots.release(capacity);
                        break; // fail closed: claim nothing more, wait for the rows in flight
                    }
                    excluded = again.get();
                    checkedAt = now;
                    if (stopRequested) { // stop requested during the check, which can take seconds: claim nothing
                        slots.release(capacity);
                        break;
                    }
                }
                first = false;
                List<ClaimedNotification> claimed;
                try {
                    // due cut-off: the instant the circuits were evaluated at, so every row claimed was counted by
                    // that evaluation (circuit breaker spec 4); the lease runs from the real now
                    claimed = notifications.claimDue(checkedAt, now.plus(lease), capacity, excluded);
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
