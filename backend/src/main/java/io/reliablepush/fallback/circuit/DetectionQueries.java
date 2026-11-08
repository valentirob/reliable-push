package io.reliablepush.fallback.circuit;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The reads of the detector (spec 4), each bounded by a {@code limit} so a quiet app costs two tiny index reads and a
 * busy one at most 1,000 cohort rows. {@code now} is the application clock of the evaluation.
 *
 * <p>Due set: the app's rows with {@code state in (PENDING, DELIVERED, DISPATCHING)} and {@code due_at in (now - window,
 * now]} (partial index {@code notification_due}), plus the rows closed {@code FALLBACK_TRIGGERED} or
 * {@code FALLBACK_FAILED} with {@code closed_at > now - window} (partial index {@code notification_closed}). Cohort and
 * resumed read {@code notification_app_created}; both leave out {@code NO_DEVICE_REGISTERED} rows, which are never
 * tracked (sidecar revision spec 2.2) and would otherwise read as missing deliveries.
 */
@Component
public class DetectionQueries {

    /** At most this many notifications are read for the cohort and for resumed. */
    static final int MAX_ROWS = 1000;

    private static final String DUE_SET = """
            (select created_at from notification
              where due_at > :from and due_at <= :now and app_id = :app
                and state in ('PENDING', 'DELIVERED', 'DISPATCHING')
              limit :cap)
            union all
            (select created_at from notification
              where closed_at > :from and app_id = :app and state in ('FALLBACK_TRIGGERED', 'FALLBACK_FAILED')
              limit :cap)
            limit :cap
            """;

    private final JdbcClient jdbc;

    DetectionQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The size of the app's due set, counted up to {@code cap}. */
    public long due(String appId, Instant now, Duration window, int cap) {
        return jdbc.sql("select count(*) from (" + DUE_SET + ") due")
                .param("app", appId)
                .param("now", Timestamp.from(now))
                .param("from", Timestamp.from(now.minus(window)))
                .param("cap", cap)
                .query(Long.class)
                .single();
    }

    /**
     * The delivered share ({@code delivered_at} set, so also NOT_READ rows the device confirmed) of the cohort: the
     * app's tracked notifications created between the oldest and newest {@code created_at} of {@code minDue} due rows, newest
     * first, at most {@value #MAX_ROWS}.
     *
     * @return {@code null} when fewer than {@code minDue} rows are due
     */
    public Double cohortRatio(String appId, Instant now, Duration window, int minDue) {
        long[] counts = jdbc.sql("with due as (" + DUE_SET + """
                        ), span as (
                            select count(*) as n, min(created_at) as lo, max(created_at) as hi from due
                        )
                        select span.n, cohort.total, cohort.delivered
                          from span cross join lateral (
                              select count(*) as total, count(c.delivered_at) as delivered
                                from (select delivered_at from notification
                                       where app_id = :app and created_at >= span.lo and created_at <= span.hi
                                         and state <> 'NO_DEVICE_REGISTERED'
                                       order by created_at desc
                                       limit :max) c
                          ) cohort
                        """)
                .param("app", appId)
                .param("now", Timestamp.from(now))
                .param("from", Timestamp.from(now.minus(window)))
                .param("cap", minDue)
                .param("max", MAX_ROWS)
                .query((rs, row) -> new long[] {rs.getLong("n"), rs.getLong("total"), rs.getLong("delivered")})
                .single();
        long n = counts[0];
        long total = counts[1];
        return n < minDue || total == 0 ? null : (double) counts[2] / total;
    }

    /**
     * Whether at least {@code closeMinDelivered} of the app's {@value #MAX_ROWS} newest notifications created after
     * {@code since} were delivered.
     */
    public boolean resumed(String appId, Instant since, int closeMinDelivered) {
        long delivered = jdbc.sql("""
                        select count(c.delivered_at) from (
                            select delivered_at from notification
                             where app_id = :app and created_at > :since and state <> 'NO_DEVICE_REGISTERED'
                             order by created_at desc
                             limit :max) c
                        """)
                .param("app", appId)
                .param("since", Timestamp.from(since))
                .param("max", MAX_ROWS)
                .query(Long.class)
                .single();
        return delivered >= closeMinDelivered;
    }
}
