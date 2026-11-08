package io.reliablepush.fallback.circuit;

import java.time.Duration;

/**
 * The fallback circuit breaker configuration (spec 2): {@code reliable-push.fallback.suspended} and
 * {@code reliable-push.fallback.circuit-breaker.*}, bound by {@link FallbackSettings}. Changes take effect on restart.
 *
 * @param suspended no fallback for any app, always; wins over everything
 * @param enabled {@code false}: no detection, every circuit reads as CLOSED
 * @param window detection window
 * @param minDue trip only when at least this many fallbacks came due in the window
 * @param maxDeliveredRatio trip when less than this share of the cohort was delivered
 * @param closeMinDelivered close when this many notifications created after the open were delivered
 * @param minOpen an open circuit stays open at least this long
 * @param forceHold how long a forced resume keeps detection off
 */
public record CircuitBreakerProperties(
        boolean suspended,
        boolean enabled,
        Duration window,
        int minDue,
        double maxDeliveredRatio,
        int closeMinDelivered,
        Duration minOpen,
        Duration forceHold) {

    private static final String KEY = "reliable-push.fallback.circuit-breaker.";

    public CircuitBreakerProperties {
        requirePositive("window", window);
        requirePositive("min-open", minOpen);
        requirePositive("force-hold", forceHold);
        if (minDue < 1) {
            throw invalid("min-due", "at least 1");
        }
        if (closeMinDelivered < 1) {
            throw invalid("close-min-delivered", "at least 1");
        }
        if (!(maxDeliveredRatio > 0 && maxDeliveredRatio <= 1)) {
            throw invalid("max-delivered-ratio", "greater than 0 and at most 1");
        }
    }

    /** The values of spec 2. */
    public static CircuitBreakerProperties defaults() {
        return new CircuitBreakerProperties(false, true, Duration.ofMinutes(5), 50, 0.10, 10, Duration.ofMinutes(1),
                Duration.ofMinutes(60));
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalid(key, "positive");
        }
    }

    private static IllegalStateException invalid(String key, String rule) {
        return new IllegalStateException(KEY + key + " must be " + rule);
    }
}
