package io.reliablepush.fallback;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The trigger-window and retry rules of spec 2 and 5, as a pure function. All durations are real (already scaled by
 * the fast clock where it applies); {@code dueAt} is the original due time, never moved by retries.
 */
final class FallbackDecision {

    /** What to do with a claimed row. */
    sealed interface Outcome {
        /** Call the webhook (the row is inside its trigger window). */
        Outcome DISPATCH = new Dispatch();
        /** Close as {@code FALLBACK_EXPIRED}: the window is over (before a call) or the next retry would be after it. */
        Outcome EXPIRE = new Expire();
        /** Close as {@code FALLBACK_FAILED}: final webhook answer, or the last allowed attempt failed. */
        Outcome FAIL = new Fail();
        /** Close as {@code FALLBACK_TRIGGERED}: the webhook accepted the call. */
        Outcome DONE = new Done();

        record Dispatch() implements Outcome {}

        record Expire() implements Outcome {}

        /** Keep the row {@code DISPATCHING} and try again at {@code at}. */
        record RetryAt(Instant at) implements Outcome {}

        record Fail() implements Outcome {}

        record Done() implements Outcome {}
    }

    private FallbackDecision() {}

    /**
     * Without a {@code lastResult} (row just claimed, webhook not called yet): {@code DISPATCH} while
     * {@code now <= dueAt + window}, else {@code EXPIRE}.
     *
     * <p>With the result of attempt number {@code attemptsSoFar} (1-based, already counted): {@code Success} →
     * {@code DONE} (even if the window ended during the call: the attempt started inside it); {@code Final} →
     * {@code FAIL}; {@code Retryable} → {@code FAIL} once {@code maxAttempts} attempts were made, else
     * {@code RETRY_AT(now + backoff[attemptsSoFar - 1])} if that instant is still {@code <= dueAt + window}, else
     * {@code EXPIRE}. When {@code backoff} is shorter than {@code maxAttempts - 1}, its last delay repeats.
     */
    static Outcome decide(
            Instant now,
            Instant dueAt,
            Duration window,
            int attemptsSoFar,
            int maxAttempts,
            List<Duration> backoff,
            WebhookResult lastResult) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(dueAt, "dueAt");
        Instant windowEnd = dueAt.plus(window);
        if (lastResult == null) {
            return now.isAfter(windowEnd) ? Outcome.EXPIRE : Outcome.DISPATCH;
        }
        return switch (lastResult) {
            case WebhookResult.Success success -> Outcome.DONE;
            case WebhookResult.Final fin -> Outcome.FAIL;
            case WebhookResult.Retryable retryable -> {
                if (attemptsSoFar >= maxAttempts) {
                    yield Outcome.FAIL;
                }
                Duration delay = backoff.get(Math.min(Math.max(attemptsSoFar, 1), backoff.size()) - 1);
                Instant next = now.plus(delay);
                yield next.isAfter(windowEnd) ? Outcome.EXPIRE : new Outcome.RetryAt(next);
            }
        };
    }
}
