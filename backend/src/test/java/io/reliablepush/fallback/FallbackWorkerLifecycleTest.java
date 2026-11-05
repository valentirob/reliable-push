package io.reliablepush.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.shared.logging.EventCapture;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The worker's shutdown hook: stop the claims first, then wait (bounded) for the rows in flight; WARN on timeout. */
class FallbackWorkerLifecycleTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final EventCapture warnings = new EventCapture(FallbackWorkerLifecycle.class.getName(), Level.WARN);

    @AfterEach
    void closeCapture() {
        warnings.close();
    }

    /** Records the calls the lifecycle makes; {@code awaitIdle} answers {@code idle} at once. */
    private static final class RecordingScheduler extends FallbackScheduler {

        private final List<String> calls = new ArrayList<>();
        private final boolean idle;

        RecordingScheduler(boolean idle) {
            super(null, null, Clock.systemUTC(), Duration.ofMinutes(2), 1, 1);
            this.idle = idle;
        }

        @Override
        public void requestStop() {
            calls.add("requestStop");
        }

        @Override
        boolean awaitIdle(Duration timeout) {
            calls.add("awaitIdle " + timeout);
            return idle;
        }

        @Override
        int rowsInFlight() {
            return 3;
        }
    }

    private List<String> warnLines() {
        return warnings.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(LogEvent::getMessage)
                .map(message -> message.getFormattedMessage())
                .toList();
    }

    @Test
    void stopRequestsTheStopBeforeWaitingForTheRowsInFlight() {
        RecordingScheduler scheduler = new RecordingScheduler(true);
        FallbackWorkerLifecycle lifecycle = new FallbackWorkerLifecycle(scheduler, TIMEOUT);
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();

        lifecycle.stop();

        assertThat(scheduler.calls).containsExactly("requestStop", "awaitIdle PT15S");
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(warnLines()).isEmpty();
    }

    @Test
    void stopThatTimesOutLogsOneWarnWithTheRowsInFlight() {
        RecordingScheduler scheduler = new RecordingScheduler(false);
        FallbackWorkerLifecycle lifecycle = new FallbackWorkerLifecycle(scheduler, TIMEOUT);
        lifecycle.start();

        lifecycle.stop();

        assertThat(scheduler.calls).containsExactly("requestStop", "awaitIdle PT15S");
        assertThat(warnLines()).containsExactly("worker stop timed out; 3 rows may be re-sent after their lease");
    }

    @Test
    void stopsBeforeTheDefaultPhase() {
        assertThat(new FallbackWorkerLifecycle(new RecordingScheduler(true), TIMEOUT).getPhase())
                .isEqualTo(FallbackWorkerLifecycle.PHASE)
                .isGreaterThan(0);
    }
}
