package io.reliablepush.fallback.circuit;

import java.time.Instant;

/**
 * One app's circuit. {@code forcedUntil} and {@code forcedBy} (admin key id, never the key) are set only while
 * {@link CircuitState#FORCED}; {@code suppressed} counts the fallbacks closed without a call since {@code since}.
 */
public record Circuit(
        String appId, CircuitState state, Instant since, Instant forcedUntil, String forcedBy, long suppressed) {

    /** The circuit of an app that has no stored row. */
    public static Circuit closed(String appId, Instant epoch) {
        return new Circuit(appId, CircuitState.CLOSED, epoch, null, null, 0);
    }
}
