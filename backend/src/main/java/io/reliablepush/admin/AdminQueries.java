package io.reliablepush.admin;

import io.reliablepush.fallback.FallbackAttemptRepository;
import io.reliablepush.notifications.NotificationState;
import io.reliablepush.notifications.PageCursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only queries of the support lookups (plain SQL through {@link JdbcClient}). */
@Component
@ConditionalOnProperty("reliable-push.admin.keys[0].id")
class AdminQueries {

    static final int MAX_DEVICES = 50;

    private static final String ITEM_COLUMNS = "id, external_id, user_id, state, policy, created_at, delivered_at,"
            + " read_at, closed_at, fallback_attempts";

    private final JdbcClient jdbc;
    private final FallbackAttemptRepository attempts;

    AdminQueries(JdbcClient jdbc, FallbackAttemptRepository attempts) {
        this.jdbc = jdbc;
        this.attempts = attempts;
    }

    /** Newest first by {@code (created_at, id)}; {@code [from, to)} on {@code created_at}; at most {@code limit} rows. */
    List<AdminViews.NotificationItem> search(
            String appId,
            String userId,
            String externalId,
            NotificationState state,
            Instant from,
            Instant to,
            PageCursor after,
            int limit) {
        StringBuilder sql = new StringBuilder("select " + ITEM_COLUMNS + " from notification where app_id = :appId");
        Map<String, Object> params = new HashMap<>();
        params.put("appId", appId);
        if (userId != null) {
            sql.append(" and user_id = :userId");
            params.put("userId", userId);
        }
        if (externalId != null) {
            sql.append(" and external_id = :externalId");
            params.put("externalId", externalId);
        }
        if (state != null) {
            sql.append(" and state = :state");
            params.put("state", state.name());
        }
        if (from != null) {
            sql.append(" and created_at >= :from");
            params.put("from", Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" and created_at < :to");
            params.put("to", Timestamp.from(to));
        }
        if (after != null) {
            sql.append(" and (created_at, id) < (:afterCreatedAt, :afterId)");
            params.put("afterCreatedAt", Timestamp.from(after.createdAt()));
            params.put("afterId", after.id());
        }
        sql.append(" order by created_at desc, id desc limit :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> new AdminViews.NotificationItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("external_id"),
                        rs.getString("user_id"),
                        NotificationState.valueOf(rs.getString("state")).external(),
                        rs.getString("policy"),
                        instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("delivered_at")),
                        instant(rs.getTimestamp("read_at")),
                        instant(rs.getTimestamp("closed_at")),
                        rs.getInt("fallback_attempts")))
                .list();
    }

    /** A notification with its owning app, so the caller can check visibility. */
    record Found(String appId, AdminViews.NotificationDetail notification) {}

    Optional<Found> find(UUID id) {
        return jdbc.sql("select app_id, timeout_minutes, due_at, " + ITEM_COLUMNS + " from notification where id = :id")
                .param("id", id)
                .query((rs, n) -> new Found(
                        rs.getString("app_id"),
                        new AdminViews.NotificationDetail(
                                rs.getObject("id", UUID.class),
                                rs.getString("external_id"),
                                rs.getString("user_id"),
                                NotificationState.valueOf(rs.getString("state")).external(),
                                rs.getString("policy"),
                                instant(rs.getTimestamp("created_at")),
                                instant(rs.getTimestamp("delivered_at")),
                                instant(rs.getTimestamp("read_at")),
                                instant(rs.getTimestamp("closed_at")),
                                rs.getInt("fallback_attempts"),
                                rs.getInt("timeout_minutes"),
                                instant(rs.getTimestamp("due_at")))))
                .optional();
    }

    AdminViews.Detail detail(AdminViews.NotificationDetail notification) {
        UUID id = notification.trackingId();
        List<AdminViews.Event> events = jdbc.sql("""
                        select e.type, e.occurred_at, e.received_at, e.late, e.device_id, d.platform
                          from notification_event e left join device d on d.id = e.device_id
                         where e.notification_id = :id order by e.occurred_at, e.id
                        """)
                .param("id", id)
                .query((rs, n) -> new AdminViews.Event(
                        rs.getString("type"),
                        instant(rs.getTimestamp("occurred_at")),
                        instant(rs.getTimestamp("received_at")),
                        rs.getBoolean("late"),
                        rs.getObject("device_id", UUID.class),
                        rs.getString("platform")))
                .list();
        List<AdminViews.Attempt> tries = attempts.forNotification(id).stream()
                .map(a -> new AdminViews.Attempt(
                        a.attempt(), a.startedAt(), a.durationMs(), a.outcome(), a.httpStatus(), a.reason()))
                .toList();
        int payloadBytes = jdbc.sql("select octet_length(fallback_payload::text) from notification where id = :id")
                .param("id", id)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return new AdminViews.Detail(notification, events, tries, payloadBytes);
    }

    /** The stored fallback payload as JSON text. */
    Optional<String> payload(UUID id) {
        return jdbc.sql("select fallback_payload::text from notification where id = :id")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    List<AdminViews.Device> devices(String appId, String userId) {
        return jdbc.sql("""
                        select id, platform, created_at, last_seen_at, revoked_at from device
                         where app_id = :appId and user_id = :userId
                         order by created_at desc, id desc limit :limit
                        """)
                .param("appId", appId)
                .param("userId", userId)
                .param("limit", MAX_DEVICES)
                .query((rs, n) -> new AdminViews.Device(
                        rs.getObject("id", UUID.class),
                        rs.getString("platform"),
                        instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("last_seen_at")),
                        instant(rs.getTimestamp("revoked_at"))))
                .list();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
