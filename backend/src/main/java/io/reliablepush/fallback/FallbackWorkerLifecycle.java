package io.reliablepush.fallback;

import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.SmartLifecycle;

/**
 * Stops the fallback worker on shutdown (SIGTERM in Kubernetes): {@link #stop()} tells the scheduler to claim nothing
 * more and waits, for at most {@code shutdownTimeout}, until the running tick has written the outcome of every row in
 * flight. The phase is high, so this runs before the web server stops and long before the DataSource is destroyed.
 * A tick that outlives the timeout is logged once at WARN; its rows are re-sent after their lease.
 */
final class FallbackWorkerLifecycle implements SmartLifecycle {

    /** Stops before anything with the default phase (web server) and before the beans it depends on are destroyed. */
    static final int PHASE = Integer.MAX_VALUE - 100;

    private static final Logger LOG = LogManager.getLogger(FallbackWorkerLifecycle.class);

    private final FallbackScheduler scheduler;
    private final Duration shutdownTimeout;
    private volatile boolean running;

    FallbackWorkerLifecycle(FallbackScheduler scheduler, Duration shutdownTimeout) {
        this.scheduler = scheduler;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        scheduler.requestStop();
        if (!scheduler.awaitIdle(shutdownTimeout)) {
            LOG.warn("worker stop timed out; {} rows may be re-sent after their lease", scheduler.rowsInFlight());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
