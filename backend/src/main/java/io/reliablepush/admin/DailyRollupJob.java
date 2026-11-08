package io.reliablepush.admin;

import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.fallback.circuit.CircuitRepository;
import io.reliablepush.shared.logging.EventLog;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The nightly rollup of per-app daily statistics (spec 3.2). Recomputes the last {@code W} complete UTC days in one
 * transaction guarded by a PostgreSQL advisory lock (another worker holding it means this run does nothing), then
 * deletes rows (daily stats and fallback circuit events) older than {@code rollup-retention-days}. Does nothing while
 * admin is disabled. Worker role only.
 */
@Component
@Profile("worker")
public class DailyRollupJob {

    /** Same constant on every worker: the transaction-scoped advisory lock of the rollup. */
    static final long LOCK_KEY = 4_242_001L;

    private static final Logger LOG = LogManager.getLogger(DailyRollupJob.class);

    private final AdminKeys keys;
    private final AdminProperties properties;
    private final ReliablePushProperties retention;
    private final DailyStatsRepository stats;
    private final CircuitRepository circuits;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final Clock clock;

    DailyRollupJob(
            AdminKeys keys,
            AdminProperties properties,
            ReliablePushProperties retention,
            DailyStatsRepository stats,
            CircuitRepository circuits,
            TransactionTemplate transactions,
            JdbcClient jdbc,
            Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.retention = retention;
        this.stats = stats;
        this.circuits = circuits;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** UTC cron {@code reliable-push.admin.rollup.cron} (03:07 by default, before retention); {@code -} disables. */
    @Scheduled(cron = "${reliable-push.admin.rollup.cron:0 7 3 * * *}", zone = "UTC")
    public void scheduled() {
        run();
    }

    /**
     * Recomputes the window and prunes old rows; never throws.
     *
     * @return the number of days written (0 when admin is disabled, another worker holds the lock, or it failed)
     */
    public int run() {
        if (!keys.enabled()) {
            return 0;
        }
        try {
            Integer written = transactions.execute(status -> {
                Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:key)")
                        .param("key", LOCK_KEY)
                        .query(Boolean.class)
                        .single();
                if (!Boolean.TRUE.equals(locked)) {
                    return 0;
                }
                LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
                List<LocalDate> days = RollupWindow.days(today, retention.retentionDays());
                days.forEach(stats::upsert);
                LocalDate cutoff = today.minusDays(properties.rollupRetentionDays());
                stats.deleteBefore(cutoff);
                circuits.deleteEventsBefore(cutoff.atStartOfDay(ZoneOffset.UTC).toInstant());
                return days.size();
            });
            int count = written == null ? 0 : written;
            if (count > 0) {
                EventLog.info("rollup.completed", Map.of("count", count));
            }
            return count;
        } catch (RuntimeException e) {
            EventLog.warn("rollup.failed", Map.of("reason", e.getClass().getSimpleName()));
            // the rollup SQL carries no personal data (app ids, days, counts), so the stack trace is safe to log
            LOG.error("daily rollup failed and was rolled back", e);
            return 0;
        }
    }
}
