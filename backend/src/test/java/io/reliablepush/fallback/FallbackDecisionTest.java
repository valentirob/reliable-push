package io.reliablepush.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.fallback.FallbackDecision.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure trigger-window / retry rules of spec 2 and 5 (unscaled minutes; the scheduler passes scaled durations). */
class FallbackDecisionTest {

    private static final Instant DUE = Instant.parse("2026-11-03T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(20);
    private static final int MAX = 5;
    private static final List<Duration> BACKOFF =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(8));

    private static final WebhookResult RETRYABLE = new WebhookResult.Retryable("http_503");

    private static Instant due(Duration offset) {
        return DUE.plus(offset);
    }

    @Test
    void dispatchesInsideWindow() {
        assertThat(FallbackDecision.decide(DUE, DUE, WINDOW, 0, MAX, BACKOFF, null)).isEqualTo(Outcome.DISPATCH);
        assertThat(FallbackDecision.decide(due(Duration.ofMinutes(19)), DUE, WINDOW, 0, MAX, BACKOFF, null))
                .isEqualTo(Outcome.DISPATCH);
        // the window end itself is still inside (now <= dueAt + window)
        assertThat(FallbackDecision.decide(due(WINDOW), DUE, WINDOW, 2, MAX, BACKOFF, null)).isEqualTo(Outcome.DISPATCH);
    }

    @Test
    void expiresWhenClaimedAfterWindow() {
        Instant justAfter = due(WINDOW).plusNanos(1000);

        assertThat(FallbackDecision.decide(justAfter, DUE, WINDOW, 0, MAX, BACKOFF, null)).isEqualTo(Outcome.EXPIRE);
        assertThat(FallbackDecision.decide(due(Duration.ofMinutes(30)), DUE, WINDOW, 1, MAX, BACKOFF, null))
                .isEqualTo(Outcome.EXPIRE);
    }

    @Test
    void retryScheduledWithBackoff() {
        Outcome first = FallbackDecision.decide(DUE, DUE, WINDOW, 1, MAX, BACKOFF, RETRYABLE);
        assertThat(first).isEqualTo(new Outcome.RetryAt(due(Duration.ofMinutes(1))));

        // attempt 2 failed at due+1m -> second backoff (2 min)
        Outcome second = FallbackDecision.decide(due(Duration.ofMinutes(1)), DUE, WINDOW, 2, MAX, BACKOFF, RETRYABLE);
        assertThat(second).isEqualTo(new Outcome.RetryAt(due(Duration.ofMinutes(3))));
    }

    @Test
    void retryBeyondWindowExpires() {
        Duration window = Duration.ofMinutes(5);

        // attempt 3 failed at due+3m: next backoff 4 min -> due+7m > due+5m
        Outcome outcome = FallbackDecision.decide(due(Duration.ofMinutes(3)), DUE, window, 3, MAX, BACKOFF, RETRYABLE);

        assertThat(outcome).isEqualTo(Outcome.EXPIRE);
    }

    @Test
    void retryLandingExactlyOnWindowEndIsScheduled() {
        Duration window = Duration.ofMinutes(7);

        Outcome outcome = FallbackDecision.decide(due(Duration.ofMinutes(3)), DUE, window, 3, MAX, BACKOFF, RETRYABLE);

        assertThat(outcome).isEqualTo(new Outcome.RetryAt(due(Duration.ofMinutes(7))));
    }

    @Test
    void finalFailureAfterMaxAttempts() {
        Outcome outcome = FallbackDecision.decide(due(Duration.ofMinutes(15)), DUE, WINDOW, MAX, MAX, BACKOFF, RETRYABLE);

        assertThat(outcome).isEqualTo(Outcome.FAIL);
    }

    @Test
    void finalResultFailsAtOnce() {
        Outcome outcome = FallbackDecision.decide(DUE, DUE, WINDOW, 1, MAX, BACKOFF, new WebhookResult.Final("http_400"));

        assertThat(outcome).isEqualTo(Outcome.FAIL);
    }

    @Test
    void successIsDoneEvenAfterTheWindow() {
        // the attempt was started inside the window; its success is never thrown away
        Outcome outcome = FallbackDecision.decide(
                due(Duration.ofMinutes(25)), DUE, WINDOW, 1, MAX, BACKOFF, new WebhookResult.Success(204));

        assertThat(outcome).isEqualTo(Outcome.DONE);
    }

    @Test
    void lastBackoffRepeatsWhenListIsShort() {
        List<Duration> shortList = List.of(Duration.ofMinutes(1));
        Duration window = Duration.ofMinutes(60);

        Outcome outcome = FallbackDecision.decide(due(Duration.ofMinutes(10)), DUE, window, 3, MAX, shortList, RETRYABLE);

        assertThat(outcome).isEqualTo(new Outcome.RetryAt(due(Duration.ofMinutes(11))));
    }
}
