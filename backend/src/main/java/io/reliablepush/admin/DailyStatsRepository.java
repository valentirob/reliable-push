package io.reliablepush.admin;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** SQL of the per-app daily statistics (spec 3.2): the one aggregate, its upsert, and the stored rows. */
@Component
class DailyStatsRepository {

    /**
     * One row per configured app for the UTC day {@code [:start, :end)}, zeros included. The platform is the one of the
     * device of the DELIVERED event, else of the READ event (null when that device was purged).
     */
    private static final String AGGREGATE = """
            select a.app_id,
                   count(n.id) as registered,
                   count(n.id) filter (where n.state = 'CANCELLED') as cancelled,
                   count(n.id) filter (where n.delivered_at is not null) as delivered,
                   count(n.id) filter (where n.read_at is not null) as read,
                   count(n.id) filter (where n.delivered_at is not null and p.platform = 'IOS') as delivered_ios,
                   count(n.id) filter (where n.delivered_at is not null and p.platform = 'ANDROID') as delivered_android,
                   count(n.id) filter (where n.state = 'FALLBACK_TRIGGERED') as fallback_triggered,
                   count(n.id) filter (where n.state = 'FALLBACK_FAILED') as fallback_failed,
                   count(n.id) filter (where n.state = 'FALLBACK_EXPIRED') as fallback_expired,
                   round(percentile_cont(0.5) within group (
                           order by extract(epoch from (n.delivered_at - n.created_at)) * 1000)
                         filter (where n.delivered_at is not null))::bigint as delivery_p50_ms,
                   round(percentile_cont(0.95) within group (
                           order by extract(epoch from (n.delivered_at - n.created_at)) * 1000)
                         filter (where n.delivered_at is not null))::bigint as delivery_p95_ms
            from unnest(cast(:apps as text[])) as a(app_id)
            left join notification n
                   on n.app_id = a.app_id and n.created_at >= :start and n.created_at < :end
            left join lateral (
                   select d.platform
                   from notification_event e
                   left join device d on d.id = e.device_id
                   where e.notification_id = n.id
                   order by (e.type = 'DELIVERED') desc, e.id
                   limit 1) p on true
            group by a.app_id
            """;

    private final JdbcClient jdbc;
    private final AppRegistry apps;
    private final Clock clock;

    DailyStatsRepository(JdbcClient jdbc, AppRegistry apps, Clock clock) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.clock = clock;
    }

    private String[] appIds() {
        return apps.all().stream().map(AppConfig::appId).toArray(String[]::new);
    }

    private static Timestamp startOf(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /** Recomputes {@code day} for every configured app (zero rows included) in one statement. */
    void upsert(LocalDate day) {
        jdbc.sql("""
                        insert into daily_stats (day, app_id, registered, cancelled, delivered, read, delivered_ios,
                            delivered_android, fallback_triggered, fallback_failed, fallback_expired,
                            delivery_p50_ms, delivery_p95_ms, computed_at)
                        select cast(:day as date), s.app_id, s.registered, s.cancelled, s.delivered, s.read,
                               s.delivered_ios, s.delivered_android, s.fallback_triggered, s.fallback_failed,
                               s.fallback_expired, s.delivery_p50_ms, s.delivery_p95_ms, :computedAt
                        from (%s) s
                        on conflict (day, app_id) do update set
                            registered = excluded.registered, cancelled = excluded.cancelled,
                            delivered = excluded.delivered, read = excluded.read,
                            delivered_ios = excluded.delivered_ios, delivered_android = excluded.delivered_android,
                            fallback_triggered = excluded.fallback_triggered,
                            fallback_failed = excluded.fallback_failed,
                            fallback_expired = excluded.fallback_expired,
                            delivery_p50_ms = excluded.delivery_p50_ms, delivery_p95_ms = excluded.delivery_p95_ms,
                            computed_at = excluded.computed_at
                        """.formatted(AGGREGATE))
                .param("day", day.toString())
                .param("apps", appIds())
                .param("start", startOf(day))
                .param("end", startOf(day.plusDays(1)))
                .param("computedAt", Timestamp.from(clock.instant()))
                .update();
    }

    /** Deletes the rows of days strictly before {@code day}. */
    int deleteBefore(LocalDate day) {
        return jdbc.sql("delete from daily_stats where day < cast(:day as date)")
                .param("day", day.toString())
                .update();
    }

    Optional<Instant> lastComputedAt() {
        return jdbc.sql("select max(computed_at) from daily_stats")
                .query((rs, n) -> {
                    Timestamp t = rs.getTimestamp(1);
                    return t == null ? null : t.toInstant();
                })
                .optional();
    }

    /** Stored rows of one app for {@code [from, toExclusive)}, oldest day first. */
    List<DailyStats> range(String appId, LocalDate from, LocalDate toExclusive) {
        return jdbc.sql("""
                        select * from daily_stats
                        where app_id = :app and day >= cast(:from as date) and day < cast(:to as date)
                        order by day
                        """)
                .param("app", appId)
                .param("from", from.toString())
                .param("to", toExclusive.toString())
                .query((rs, n) -> map(rs, rs.getDate("day").toLocalDate()))
                .list();
    }

    /** The same numbers computed now from {@code notification} for one app and day (not stored). */
    DailyStats live(String appId, LocalDate day) {
        return jdbc.sql("select * from (" + AGGREGATE + ") s where s.app_id = :app")
                .param("apps", appIds())
                .param("app", appId)
                .param("start", startOf(day))
                .param("end", startOf(day.plusDays(1)))
                .query((rs, n) -> map(rs, day))
                .single();
    }

    private static DailyStats map(java.sql.ResultSet rs, LocalDate day) throws java.sql.SQLException {
        long p50 = rs.getLong("delivery_p50_ms");
        Long p50Value = rs.wasNull() ? null : p50;
        long p95 = rs.getLong("delivery_p95_ms");
        Long p95Value = rs.wasNull() ? null : p95;
        return new DailyStats(
                day,
                rs.getString("app_id"),
                new Counts(
                        rs.getInt("registered"),
                        rs.getInt("cancelled"),
                        rs.getInt("delivered"),
                        rs.getInt("read"),
                        rs.getInt("fallback_triggered"),
                        rs.getInt("fallback_failed"),
                        rs.getInt("fallback_expired")),
                rs.getInt("delivered_ios"),
                rs.getInt("delivered_android"),
                p50Value,
                p95Value);
    }
}
