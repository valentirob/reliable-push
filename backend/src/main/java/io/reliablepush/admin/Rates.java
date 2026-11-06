package io.reliablepush.admin;

/** Rate definition of spec 3.3, shared by live windows and rollups. */
final class Rates {

    private Rates() {}

    /** {@code part / (registered - cancelled)}; {@code null} when that denominator is zero. */
    static Double of(int part, Counts counts) {
        int n = counts.registered() - counts.cancelled();
        return n == 0 ? null : (double) part / n;
    }
}
