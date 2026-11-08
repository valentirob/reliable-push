package io.reliablepush.fallback.circuit;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.fallback.circuit.CircuitTransitions.Transition;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The transition table of spec 4 as a pure function: every row and its boundaries. */
class CircuitTransitionsTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final CircuitBreakerProperties P = CircuitBreakerProperties.defaults();
    private static final String APP = "shop";

    private static Circuit closed() {
        return Circuit.closed(APP, Instant.EPOCH);
    }

    private static Circuit open(Duration ago, long suppressed) {
        return new Circuit(APP, CircuitState.OPEN, NOW.minus(ago), null, null, suppressed);
    }

    private static Circuit forced(Duration ago, Instant until) {
        return new Circuit(APP, CircuitState.FORCED, NOW.minus(ago), until, "ops-1", 3);
    }

    private static Observation storm() {
        return new Observation(50, 0.0, false);
    }

    private static Observation quiet() {
        return new Observation(0, null, false);
    }

    private static Observation resumed() {
        return new Observation(0, null, true);
    }

    // ---- CLOSED -> OPEN ----------------------------------------------------------------------------------------

    @Test
    void closedOpensOnManyDueAndLowRatio() {
        Optional<Transition> t = CircuitTransitions.next(closed(), new Observation(50, 0.05, false), NOW, P);

        assertThat(t).hasValueSatisfying(tr -> {
            assertThat(tr.transition()).isEqualTo("OPENED");
            assertThat(tr.reason()).isEqualTo("tracking_missing");
            assertThat(tr.next()).isEqualTo(new Circuit(APP, CircuitState.OPEN, NOW, null, null, 0));
        });
    }

    @Test
    void closedStaysBelowMinDue() {
        assertThat(CircuitTransitions.next(closed(), new Observation(49, null, false), NOW, P)).isEmpty();
        // a ratio is never computed below min-due, but even a stray one does not trip
        assertThat(CircuitTransitions.next(closed(), new Observation(49, 0.0, false), NOW, P)).isEmpty();
    }

    @Test
    void closedStaysWhenRatioAtTheLimit() {
        assertThat(CircuitTransitions.next(closed(), new Observation(50, 0.10, false), NOW, P)).isEmpty();
        assertThat(CircuitTransitions.next(closed(), new Observation(50, 0.7, false), NOW, P)).isEmpty();
    }

    @Test
    void closedStaysWithoutRatio() {
        assertThat(CircuitTransitions.next(closed(), new Observation(50, null, false), NOW, P)).isEmpty();
    }

    @Test
    void closedIgnoresResumed() {
        assertThat(CircuitTransitions.next(closed(), resumed(), NOW, P)).isEmpty();
    }

    // ---- OPEN -> CLOSED ----------------------------------------------------------------------------------------

    @Test
    void openClosesWhenResumedAfterMinOpen() {
        Optional<Transition> t = CircuitTransitions.next(open(Duration.ofMinutes(1), 42), resumed(), NOW, P);

        assertThat(t).hasValueSatisfying(tr -> {
            assertThat(tr.transition()).isEqualTo("CLOSED");
            assertThat(tr.reason()).isEqualTo("delivered_resumed");
            assertThat(tr.next()).isEqualTo(new Circuit(APP, CircuitState.CLOSED, NOW, null, null, 0));
        });
    }

    @Test
    void openStaysBeforeMinOpen() {
        assertThat(CircuitTransitions.next(open(Duration.ofSeconds(59), 0), resumed(), NOW, P)).isEmpty();
    }

    @Test
    void openStaysWithoutResumed() {
        assertThat(CircuitTransitions.next(open(Duration.ofHours(1), 0), quiet(), NOW, P)).isEmpty();
        assertThat(CircuitTransitions.next(open(Duration.ofHours(1), 0), storm(), NOW, P)).isEmpty();
    }

    // ---- operator resume -> FORCED -----------------------------------------------------------------------------

    @Test
    void forceFromOpenHoldsForForceHold() {
        Transition t = CircuitTransitions.force(open(Duration.ofMinutes(5), 12), NOW, "ops-1", P);

        assertThat(t.transition()).isEqualTo("FORCED");
        assertThat(t.reason()).isEqualTo("forced");
        assertThat(t.next()).isEqualTo(
                new Circuit(APP, CircuitState.FORCED, NOW, NOW.plus(Duration.ofMinutes(60)), "ops-1", 0));
    }

    @Test
    void forceFromClosed() {
        Transition t = CircuitTransitions.force(closed(), NOW, "ops-2", P);

        assertThat(t.next().state()).isEqualTo(CircuitState.FORCED);
        assertThat(t.next().forcedBy()).isEqualTo("ops-2");
        assertThat(t.next().forcedUntil()).isEqualTo(NOW.plus(P.forceHold()));
    }

    @Test
    void forceAgainRefreshesTheHold() {
        Transition t = CircuitTransitions.force(forced(Duration.ofMinutes(30), NOW.plusSeconds(60)), NOW, "ops-2", P);

        assertThat(t.next()).isEqualTo(
                new Circuit(APP, CircuitState.FORCED, NOW, NOW.plus(P.forceHold()), "ops-2", 0));
    }

    // ---- FORCED -> CLOSED --------------------------------------------------------------------------------------

    @Test
    void forcedEndsWhenForcedUntilReached() {
        Optional<Transition> t = CircuitTransitions.next(forced(Duration.ofMinutes(60), NOW), quiet(), NOW, P);

        assertThat(t).hasValueSatisfying(tr -> {
            assertThat(tr.transition()).isEqualTo("FORCE_ENDED");
            assertThat(tr.reason()).isEqualTo("force_expired");
            assertThat(tr.next()).isEqualTo(new Circuit(APP, CircuitState.CLOSED, NOW, null, null, 0));
        });
    }

    @Test
    void forcedEndsWhenResumed() {
        Optional<Transition> t =
                CircuitTransitions.next(forced(Duration.ofSeconds(1), NOW.plusSeconds(3600)), resumed(), NOW, P);

        assertThat(t).hasValueSatisfying(tr -> {
            assertThat(tr.transition()).isEqualTo("FORCE_ENDED");
            assertThat(tr.reason()).isEqualTo("delivered_resumed");
            assertThat(tr.next().state()).isEqualTo(CircuitState.CLOSED);
        });
    }

    @Test
    void forcedExpiryWinsOverResumed() {
        Optional<Transition> t = CircuitTransitions.next(forced(Duration.ofMinutes(61), NOW), resumed(), NOW, P);

        assertThat(t).map(Transition::reason).hasValue("force_expired");
    }

    @Test
    void forcedIgnoresABadRatio() {
        assertThat(CircuitTransitions.next(forced(Duration.ofMinutes(1), NOW.plusSeconds(1)), storm(), NOW, P))
                .isEmpty();
    }

    @Test
    void forcedStaysJustBeforeForcedUntil() {
        assertThat(CircuitTransitions.next(forced(Duration.ofMinutes(1), NOW.plusNanos(1000)), quiet(), NOW, P))
                .isEmpty();
    }
}
