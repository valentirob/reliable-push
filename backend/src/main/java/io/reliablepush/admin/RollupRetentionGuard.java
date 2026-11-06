package io.reliablepush.admin;

import io.reliablepush.notifications.RetentionGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Retention runs only after a successful rollup (spec 3.2): purge is allowed when the rollup schedule is disabled
 * ({@code -}) or {@code max(computed_at)} of {@code daily_stats} is at most 20 hours old. Reads the database, so it
 * holds with several workers.
 */
@Component
@Profile("worker")
@ConditionalOnProperty("reliable-push.admin.keys[0].id")
class RollupRetentionGuard implements RetentionGuard {

    static final Duration MAX_AGE = Duration.ofHours(20);

    private final AdminProperties properties;
    private final DailyStatsRepository stats;
    private final Clock clock;

    RollupRetentionGuard(AdminProperties properties, DailyStatsRepository stats, Clock clock) {
        this.properties = properties;
        this.stats = stats;
        this.clock = clock;
    }

    @Override
    public boolean allowPurge() {
        if ("-".equals(properties.rollup().cron())) {
            return true;
        }
        Instant cutoff = clock.instant().minus(MAX_AGE);
        return stats.lastComputedAt().filter(at -> !at.isBefore(cutoff)).isPresent();
    }
}
