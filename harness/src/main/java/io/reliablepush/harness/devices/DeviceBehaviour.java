package io.reliablepush.harness.devices;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a simulated device does when a push arrives. All durations are wall-clock, measured from the moment the push
 * is received (the backend's fast clock does not affect them). String forms: {@code deliver:3s}, {@code read:2s:5s},
 * {@code never}, {@code late:30s}; durations are {@code <n>ms}, {@code <n>s} or {@code <n>m}.
 */
public sealed interface DeviceBehaviour
        permits DeviceBehaviour.DeliverAfter, DeviceBehaviour.ReadAfter, DeviceBehaviour.Never,
                DeviceBehaviour.OfflineThenLate {

    /** Posts DELIVERED after {@code d}. */
    record DeliverAfter(Duration d) implements DeviceBehaviour {
        public DeliverAfter {
            requireNonNegative(d);
        }
    }

    /** Posts DELIVERED after {@code deliver} and READ after {@code read} (both from receipt, {@code read >= deliver}). */
    record ReadAfter(Duration deliver, Duration read) implements DeviceBehaviour {
        public ReadAfter {
            requireNonNegative(deliver);
            requireNonNegative(read);
            if (read.compareTo(deliver) < 0) {
                throw new IllegalArgumentException("invalid behaviour: read time must not be before deliver time");
            }
        }
    }

    /** Never reports anything. */
    record Never() implements DeviceBehaviour {}

    /** Reports DELIVERED only after {@code late} (typically after the fallback fired). */
    record OfflineThenLate(Duration late) implements DeviceBehaviour {
        public OfflineThenLate {
            requireNonNegative(late);
        }
    }

    Pattern DURATION = Pattern.compile("(\\d{1,9})(ms|s|m)");

    static DeviceBehaviour parse(String text) {
        if (text == null || text.isBlank()) {
            throw bad(text);
        }
        String[] p = text.strip().split(":", -1);
        try {
            return switch (p[0]) {
                case "deliver" -> p.length == 2 ? new DeliverAfter(duration(p[1])) : fail(text);
                case "read" -> p.length == 3 ? new ReadAfter(duration(p[1]), duration(p[2])) : fail(text);
                case "never" -> p.length == 1 ? new Never() : fail(text);
                case "late" -> p.length == 2 ? new OfflineThenLate(duration(p[1])) : fail(text);
                default -> fail(text);
            };
        } catch (IllegalArgumentException e) {
            throw bad(text);
        }
    }

    private static DeviceBehaviour fail(String text) {
        throw bad(text);
    }

    private static IllegalArgumentException bad(String text) {
        return new IllegalArgumentException("invalid behaviour '" + text
                + "': expected deliver:<d>, read:<d>:<d>, never or late:<d> with d like 500ms, 3s or 2m");
    }

    private static Duration duration(String s) {
        Matcher m = DURATION.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException(s);
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            default -> Duration.ofMinutes(n);
        };
    }

    private static void requireNonNegative(Duration d) {
        if (Objects.requireNonNull(d, "duration").isNegative()) {
            throw new IllegalArgumentException("invalid behaviour: duration must not be negative");
        }
    }
}
