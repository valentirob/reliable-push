package io.reliablepush.fallback.circuit;

import java.time.Instant;
import java.util.Optional;

/**
 * The transition table of spec 4 as a pure function. A new circuit starts its {@code since} at {@code now} with a
 * {@code suppressed} count of 0; the count of the finished period goes to the event.
 */
public final class CircuitTransitions {

    static final String OPENED = "OPENED";
    static final String CLOSED = "CLOSED";
    static final String FORCED = "FORCED";
    static final String FORCE_ENDED = "FORCE_ENDED";

    static final String TRACKING_MISSING = "tracking_missing";
    static final String DELIVERED_RESUMED = "delivered_resumed";
    static final String FORCED_REASON = "forced";
    static final String FORCE_EXPIRED = "force_expired";

    /**
     * One transition: the circuit to store, and the event's {@code transition} and {@code reason}.
     */
    public record Transition(Circuit next, String transition, String reason) {}

    private CircuitTransitions() {}

    /**
     * The automatic transition of {@code current}, if any.
     * <ul>
     *   <li>CLOSED → OPEN when {@code due >= min-due} and {@code ratio < max-delivered-ratio};</li>
     *   <li>OPEN → CLOSED when {@code now - since >= min-open} and resumed;</li>
     *   <li>FORCED → CLOSED when {@code now >= forced_until} ({@code force_expired}, wins) or resumed; a bad ratio is
     *       ignored (detection is off while forced).</li>
     * </ul>
     */
    public static Optional<Transition> next(
            Circuit current, Observation obs, Instant now, CircuitBreakerProperties p) {
        String app = current.appId();
        return switch (current.state()) {
            case CLOSED -> obs.due() >= p.minDue() && obs.ratio() != null && obs.ratio() < p.maxDeliveredRatio()
                    ? Optional.of(new Transition(open(app, now), OPENED, TRACKING_MISSING))
                    : Optional.empty();
            case OPEN -> !now.isBefore(current.since().plus(p.minOpen())) && obs.resumed()
                    ? Optional.of(new Transition(Circuit.closed(app, now), CLOSED, DELIVERED_RESUMED))
                    : Optional.empty();
            case FORCED -> {
                if (current.forcedUntil() == null || !now.isBefore(current.forcedUntil())) {
                    yield Optional.of(new Transition(Circuit.closed(app, now), FORCE_ENDED, FORCE_EXPIRED));
                }
                yield obs.resumed()
                        ? Optional.of(new Transition(Circuit.closed(app, now), FORCE_ENDED, DELIVERED_RESUMED))
                        : Optional.empty();
            }
        };
    }

    /**
     * The operator resume (spec 6): from any state to FORCED until {@code now + force-hold}. Forcing a FORCED circuit
     * again restarts the hold.
     */
    public static Transition force(Circuit current, Instant now, String adminKeyId, CircuitBreakerProperties p) {
        Circuit next = new Circuit(current.appId(), CircuitState.FORCED, now, now.plus(p.forceHold()), adminKeyId, 0);
        return new Transition(next, FORCED, FORCED_REASON);
    }

    private static Circuit open(String app, Instant now) {
        return new Circuit(app, CircuitState.OPEN, now, null, null, 0);
    }
}
