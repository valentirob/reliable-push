package io.reliablepush.harness.scenarios.catalog;

import io.reliablepush.harness.scenarios.Scenario;
import java.time.Duration;

/**
 * Shared timings of the catalogue, derived from the E2E environment (fast clock: 1 "minute" = 1 s; app {@code shop}:
 * timeout 5, trigger window 20, backoff 1,2,4,8, max-attempts 5; workers: lease 12, poll 500 ms). Waits are generous
 * but bounded; no assertion depends on exact wall-clock timings.
 */
abstract class CatalogScenario implements Scenario {

    static final String SHOP = "shop";
    static final String NEWS = "news";
    static final String LIMITED = "limited";
    /** Notification timeout in (fast-clock) minutes = seconds. */
    static final int TIMEOUT_MINUTES = 5;
    /** A device event or a cancel takes effect well within this. */
    static final Duration QUICK = Duration.ofSeconds(10);
    /** Timeout 5 + trigger window 20 + margin 10: every fallback outcome is final by then. */
    static final Duration FALLBACK_WAIT = Duration.ofSeconds(5 + 20 + 10);
    /** "No webhook" checks wait this long after sending (timeout 5 + a few worker polls). */
    static final Duration QUIET_AFTER_SEND = Duration.ofSeconds(8);
    /** After a final fallback state: time for any unexpected extra webhook call to show up. */
    static final Duration SETTLE = Duration.ofSeconds(3);

    private final String name;
    private final String description;

    CatalogScenario(String name, String description) {
        this.name = name;
        this.description = description;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final String description() {
        return description;
    }

    @Override
    public boolean externalControl() {
        return false;
    }
}
