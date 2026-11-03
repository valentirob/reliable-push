package io.reliablepush.notifications;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of notifications (plain SQL through {@link JdbcClient}). Every state change is a conditional update
 * ({@code WHERE state IN (...)}), so concurrent writers cannot overwrite each other (spec 2, race safety); every
 * update that closes a row sets {@code closed_at} and clears {@code due_at}. Used by the {@code notifications},
 * {@code tracking} and {@code fallback} modules through these public methods only.
 */
@Repository
public class NotificationRepository {

    private static final String COLUMNS = "id, app_id, user_id, external_id, policy, timeout_minutes,"
            + " fallback_payload::text as fallback_payload, state, created_at, due_at, delivered_at, read_at, closed_at";

    private static final RowMapper<Notification> MAPPER = NotificationRepository::map;

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the row unless the app already has one with the same {@code externalId}. Atomic and race-safe: of any
     * number of concurrent inserts with the same {@code (app_id, external_id)} exactly one returns {@code true}
     * (the others wait for it and do nothing), so the caller can read the winner with {@link #findByExternalId}.
     * The conflict target repeats the predicate of the partial unique index.
     *
     * @return {@code true} if the row was inserted
     */
    public boolean insert(Notification n) {
        return jdbc.sql("""
                        insert into notification (id, app_id, user_id, external_id, policy, timeout_minutes,
                                                  fallback_payload, state, created_at, due_at)
                        values (:id, :appId, :userId, :externalId, :policy, :timeout,
                                cast(:payload as jsonb), :state, :createdAt, :dueAt)
                        on conflict (app_id, external_id) where external_id is not null do nothing
                        """)
                        .param("id", n.id())
                        .param("appId", n.appId())
                        .param("userId", n.userId())
                        .param("externalId", n.externalId())
                        .param("policy", n.policy().name())
                        .param("timeout", n.timeoutMinutes())
                        .param("payload", n.fallbackPayload())
                        .param("state", n.state().name())
                        .param("createdAt", timestamp(n.createdAt()))
                        .param("dueAt", timestamp(n.dueAt()))
                        .update()
                == 1;
    }

    public Optional<Notification> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from notification where id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public Optional<Notification> findByExternalId(String appId, String externalId) {
        return jdbc.sql("select " + COLUMNS + " from notification where app_id = :appId and external_id = :externalId")
                .param("appId", appId)
                .param("externalId", externalId)
                .query(MAPPER)
                .optional();
    }

    /**
     * Newest first by {@code (created_at desc, id desc)}, scoped to {@code appId}. All filters are optional;
     * {@code after} is the keyset position of the last row of the previous page (exclusive).
     */
    List<Notification> page(
            String appId, String userId, NotificationState state, Instant since, PageCursor after, int limit) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from notification where app_id = :appId");
        Map<String, Object> params = new HashMap<>();
        params.put("appId", appId);
        if (userId != null) {
            sql.append(" and user_id = :userId");
            params.put("userId", userId);
        }
        if (state != null) {
            sql.append(" and state = :state");
            params.put("state", state.name());
        }
        if (since != null) {
            sql.append(" and created_at >= :since");
            params.put("since", timestamp(since));
        }
        if (after != null) {
            sql.append(" and (created_at, id) < (:afterCreatedAt, :afterId)");
            params.put("afterCreatedAt", timestamp(after.createdAt()));
            params.put("afterId", after.id());
        }
        sql.append(" order by created_at desc, id desc limit :limit");
        params.put("limit", limit);

        return jdbc.sql(sql.toString()).params(params).query(MAPPER).list();
    }

    /**
     * Cancels a notification of {@code appId} that is still {@code PENDING} or {@code DELIVERED}: state
     * {@code CANCELLED}, {@code closed_at = at}, {@code due_at} cleared.
     *
     * @return {@code false} if no row matched (unknown id, other app, or a state that cannot be cancelled)
     */
    public boolean cancel(UUID id, String appId, Instant at) {
        return jdbc.sql("""
                        update notification
                           set state = 'CANCELLED', closed_at = :at, due_at = null, next_attempt_at = null
                         where id = :id and app_id = :appId and state in ('PENDING', 'DELIVERED')
                        """)
                        .param("at", timestamp(at))
                        .param("id", id)
                        .param("appId", appId)
                        .update()
                == 1;
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Notification map(ResultSet rs, int row) throws SQLException {
        return new Notification(
                rs.getObject("id", UUID.class),
                rs.getString("app_id"),
                rs.getString("user_id"),
                rs.getString("external_id"),
                FallbackPolicy.valueOf(rs.getString("policy")),
                rs.getInt("timeout_minutes"),
                rs.getString("fallback_payload"),
                NotificationState.valueOf(rs.getString("state")),
                instant(rs, "created_at"),
                instant(rs, "due_at"),
                instant(rs, "delivered_at"),
                instant(rs, "read_at"),
                instant(rs, "closed_at"));
    }
}
