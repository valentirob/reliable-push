package io.reliablepush.fallback.circuit;

/**
 * What the detector saw for one app at one evaluation (spec 4). The detector reads only what the circuit's state needs:
 * {@code due} and {@code ratio} for a CLOSED circuit, {@code resumed} for an OPEN or FORCED one.
 *
 * @param due size of the due set, counted up to {@code min-due}
 * @param ratio delivered share of the cohort; {@code null} when {@code due < min-due} (no cohort read)
 * @param resumed at least {@code close-min-delivered} notifications created after the circuit's {@code since} were
 *     delivered
 */
public record Observation(long due, Double ratio, boolean resumed) {}
