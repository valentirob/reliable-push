package io.reliablepush.fallback.circuit;

/** State of an app's fallback circuit (spec 3). An app without a stored row is {@link #CLOSED}. */
public enum CircuitState {
    CLOSED,
    OPEN,
    FORCED
}
