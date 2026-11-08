package io.reliablepush.admin;

import io.reliablepush.notifications.RetentionGuard;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention runs only after a successful rollup (spec 3.2): purge is allowed when the rollup schedule is disabled
 * ({@code -}) or {@code max(computed_at)} of {@code daily_stats} is at most 20 hours old. A rollup still running
 * (it holds its advisory lock and its rows become visible only at commit) is waited for, up to {@link #ROLLUP_WAIT};
 * a wait that runs out vetoes the run with {@code rollup_running}. Reads the database, so it holds with several
 * workers.
 */
@Component
@Profile("worker")
@ConditionalOnProperty("reliable-push.admin.keys[0].id")
class RollupRetentionGuard implements RetentionGuard {

    static final Duration MAX_AGE = Duration.ofHours(20);

    /** How long retention waits for a running rollup before it skips the night. */
    static final Duration ROLLUP_WAIT = Duration.ofMinutes(30);

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final AdminProperties properties;
    private final DailyStatsRepository stats;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Duration wait;

    @Autowired
    RollupRetentionGuard(
            AdminProperties properties, DailyStatsRepository stats, Clock clock, TransactionTemplate transactions) {
        this(properties, stats, clock, transactions, ROLLUP_WAIT);
    }

    RollupRetentionGuard(
            AdminProperties properties,
            DailyStatsRepository stats,
            Clock clock,
            TransactionTemplate transactions,
            Duration wait) {
        this.properties = properties;
        this.stats = stats;
        this.clock = clock;
        this.transactions = transactions;
        this.wait = wait;
    }

    @Override
    public Optional<String> veto() {
        if ("-".equals(properties.rollup().cron())) {
            return Optional.empty();
        }
        Optional<Instant> last;
        try {
            // read after the lock is granted: under READ COMMITTED that statement sees the rollup's committed rows
            last = transactions.execute(status -> {
                stats.awaitRollupLock(wait);
                return stats.lastComputedAt();
            });
        } catch (DataAccessException e) {
            if (lockNotAvailable(e)) {
                return Optional.of("rollup_running");
            }
            throw e;
        }
        Instant cutoff = clock.instant().minus(MAX_AGE);
        boolean fresh = last != null && last.filter(at -> !at.isBefore(cutoff)).isPresent();
        return fresh ? Optional.empty() : Optional.of("rollup_missing");
    }

    private static boolean lockNotAvailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
