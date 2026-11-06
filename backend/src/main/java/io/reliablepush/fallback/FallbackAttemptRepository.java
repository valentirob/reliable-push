package io.reliablepush.fallback;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence of the fallback worker's webhook attempts (plain SQL through {@link JdbcClient}). */
@Repository
public class FallbackAttemptRepository {

    private final JdbcClient jdbc;

    public FallbackAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(FallbackAttempt a) {
        jdbc.sql("""
                        insert into fallback_attempt
                          (notification_id, attempt, started_at, duration_ms, outcome, http_status, reason)
                        values (:id, :attempt, :started, :duration, :outcome, :status, :reason)
                        """)
                .param("id", a.notificationId())
                .param("attempt", a.attempt())
                .param("started", Timestamp.from(a.startedAt()))
                .param("duration", a.durationMs())
                .param("outcome", a.outcome())
                .param("status", a.httpStatus())
                .param("reason", a.reason())
                .update();
    }

    /** The attempts of one notification, oldest first. */
    public List<FallbackAttempt> forNotification(UUID notificationId) {
        return jdbc.sql("""
                        select notification_id, attempt, started_at, duration_ms, outcome, http_status, reason
                          from fallback_attempt where notification_id = :id order by attempt
                        """)
                .param("id", notificationId)
                .query((rs, n) -> new FallbackAttempt(
                        rs.getObject("notification_id", UUID.class),
                        rs.getInt("attempt"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getLong("duration_ms"),
                        rs.getString("outcome"),
                        rs.getObject("http_status", Integer.class),
                        rs.getString("reason")))
                .list();
    }
}
