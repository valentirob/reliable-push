package io.reliablepush.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Live overview (spec 4): counts of a window by {@code created_at} and the current backlog, per app. */
@Component
@ConditionalOnProperty("reliable-push.admin.keys[0].id")
class OverviewQuery {

    /** The accepted {@code window} values. */
    enum Window {
        H1("1h", Duration.ofHours(1)),
        H24("24h", Duration.ofHours(24)),
        D7("7d", Duration.ofDays(7));

        final String external;
        final Duration length;

        Window(String external, Duration length) {
            this.external = external;
            this.length = length;
        }

        static Window parse(String value) {
            for (Window w : values()) {
                if (w.external.equals(value)) {
                    return w;
                }
            }
            return null;
        }
    }

    record Now(int pending, int dueUnclaimed, int dispatching, Long oldestDueAgeSeconds) {}

    record Overview(
            String appId,
            String window,
            int registered,
            int cancelled,
            int delivered,
            int read,
            int fallbackTriggered,
            int fallbackFailed,
            int fallbackExpired,
            Double deliveryRate,
            Double readRate,
            Double fallbackRate,
            Now now) {}

    private static final Now NO_BACKLOG = new Now(0, 0, 0, null);
    private static final Counts NO_COUNTS = new Counts(0, 0, 0, 0, 0, 0, 0);

    private final JdbcClient jdbc;
    private final Clock clock;

    OverviewQuery(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One entry per app of {@code apps}, in the given order (apps without rows are zeros). */
    List<Overview> overview(List<String> apps, Window window) {
        Instant now = clock.instant();
        Map<String, Counts> counts = counts(apps, now.minus(window.length));
        Map<String, Now> backlog = backlog(apps, now);
        return apps.stream()
                .map(app -> {
                    Counts c = counts.getOrDefault(app, NO_COUNTS);
                    return new Overview(
                            app,
                            window.external,
                            c.registered(),
                            c.cancelled(),
                            c.delivered(),
                            c.read(),
                            c.fallbackTriggered(),
                            c.fallbackFailed(),
                            c.fallbackExpired(),
                            Rates.of(c.delivered(), c),
                            Rates.of(c.read(), c),
                            Rates.of(c.fallbackTriggered(), c),
                            backlog.getOrDefault(app, NO_BACKLOG));
                })
                .toList();
    }

    private Map<String, Counts> counts(Collection<String> apps, Instant from) {
        Map<String, Counts> result = new HashMap<>();
        jdbc.sql("""
                        select app_id,
                               count(*) as registered,
                               count(*) filter (where state = 'CANCELLED') as cancelled,
                               count(*) filter (where delivered_at is not null) as delivered,
                               count(*) filter (where read_at is not null) as read,
                               count(*) filter (where state = 'FALLBACK_TRIGGERED') as fallback_triggered,
                               count(*) filter (where state = 'FALLBACK_FAILED') as fallback_failed,
                               count(*) filter (where state = 'FALLBACK_EXPIRED') as fallback_expired
                        from notification
                        where app_id = any(:apps) and created_at >= :from
                        group by app_id
                        """)
                .param("apps", apps.toArray(String[]::new))
                .param("from", Timestamp.from(from))
                .query((rs, n) -> {
                    result.put(
                            rs.getString("app_id"),
                            new Counts(
                                    rs.getInt("registered"),
                                    rs.getInt("cancelled"),
                                    rs.getInt("delivered"),
                                    rs.getInt("read"),
                                    rs.getInt("fallback_triggered"),
                                    rs.getInt("fallback_failed"),
                                    rs.getInt("fallback_expired")));
                    return null;
                })
                .list();
        return result;
    }

    private Map<String, Now> backlog(Collection<String> apps, Instant now) {
        Map<String, Now> result = new HashMap<>();
        Timestamp at = Timestamp.from(now);
        jdbc.sql("""
                        select app_id,
                               count(*) filter (where state in ('PENDING', 'DELIVERED') and due_at > :now) as pending,
                               count(*) filter (where state in ('PENDING', 'DELIVERED') and due_at <= :now) as due_unclaimed,
                               count(*) filter (where state = 'DISPATCHING') as dispatching,
                               min(due_at) filter (where state in ('PENDING', 'DELIVERED') and due_at <= :now) as oldest_due
                        from notification
                        where app_id = any(:apps) and state in ('PENDING', 'DELIVERED', 'DISPATCHING')
                        group by app_id
                        """)
                .param("apps", apps.toArray(String[]::new))
                .param("now", at)
                .query((rs, n) -> {
                    Timestamp oldest = rs.getTimestamp("oldest_due");
                    Long age = oldest == null ? null : Math.max(0, Duration.between(oldest.toInstant(), now).toSeconds());
                    result.put(
                            rs.getString("app_id"),
                            new Now(rs.getInt("pending"), rs.getInt("due_unclaimed"), rs.getInt("dispatching"), age));
                    return null;
                })
                .list();
        return result;
    }
}
