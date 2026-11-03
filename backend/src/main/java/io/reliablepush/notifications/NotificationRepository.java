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
 * update that closes a row sets {@code closed_at} and clears {@code due_at} (a closed row never waits for the worker). Used by the {@code notifications},
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

    /**
     * The device reported {@code DELIVERED}: {@code PENDING -> DELIVERED}, {@code delivered_at = at}. Under policy
     * {@code NOT_DELIVERED} the policy is now satisfied, so the row is closed ({@code closed_at = at}, {@code due_at}
     * cleared) and the fallback worker no longer claims it; under {@code NOT_READ} it stays due. A conditional update
     * on {@code state = 'PENDING'}: it races with the worker's claim and with cancel, and exactly one wins.
     *
     * @return {@code true} if the row changed (it was still {@code PENDING})
     */
    public boolean markDelivered(UUID id, Instant at) {
        return jdbc.sql("""
                        update notification
                           set state = 'DELIVERED', delivered_at = :at,
                               due_at = case when policy = 'NOT_DELIVERED' then null else due_at end,
                               closed_at = case when policy = 'NOT_DELIVERED' then :at else closed_at end
                         where id = :id and state = 'PENDING'
                        """)
                        .param("at", timestamp(at))
                        .param("id", id)
                        .update()
                == 1;
    }

    /**
     * The device reported {@code READ}: {@code PENDING | DELIVERED -> READ}, {@code read_at = at}, {@code delivered_at}
     * set when still unset (read implies delivered), {@code due_at} cleared, {@code closed_at = at} unless a
     * {@code NOT_DELIVERED} delivery closed the row earlier (a closed {@code DELIVERED} row can still become
     * {@code READ}; its {@code closed_at} is kept).
     *
     * @return {@code true} if the row changed
     */
    public boolean markRead(UUID id, Instant at) {
        return jdbc.sql("""
                        update notification
                           set state = 'READ', read_at = :at, delivered_at = coalesce(delivered_at, :at),
                               due_at = null, closed_at = coalesce(closed_at, :at)
                         where id = :id and state in ('PENDING', 'DELIVERED')
                        """)
                        .param("at", timestamp(at))
                        .param("id", id)
                        .update()
                == 1;
    }

    /**
     * Fallback worker: claims up to {@code batch} due rows (spec 2, claim query) and leases them until
     * {@code leaseUntil}. Selection and update are one statement, hence one transaction: due rows are locked with
     * {@code FOR NO KEY UPDATE SKIP LOCKED} (rows another worker is claiming right now are skipped, and event inserts
     * referencing a row are not blocked), then set to {@code DISPATCHING} with
     * {@code next_attempt_at = lease_until = leaseUntil}. A row whose {@code lease_until} was still set is reported as
     * re-claimed (its previous holder never persisted an outcome); its lease is replaced, which fences the previous
     * holder's outcome updates.
     * Concurrent workers therefore never claim the same row twice; a row whose lease ran out (crashed worker) or whose
     * retry backoff is over is due again. {@code due_at} and {@code fallback_attempts} are not changed: the trigger
     * window is measured from the original due time, and attempts are counted when an outcome is persisted.
     * A {@code DELIVERED} row is only due under policy {@code NOT_READ}: {@code NOT_DELIVERED} rows lose their
     * {@code due_at} on delivery.
     *
     * @return the claimed rows, each with its state before the claim
     */
    public List<ClaimedNotification> claimDue(Instant now, Instant leaseUntil, int batch) {
        return jdbc.sql("""
                        with due as (
                            select id, state as previous_state, lease_until is not null as reclaimed
                              from notification
                             where (state in ('PENDING', 'DELIVERED') and due_at <= :now)
                                or (state = 'DISPATCHING' and next_attempt_at <= :now)
                             order by coalesce(next_attempt_at, due_at)
                             limit :batch
                               for no key update skip locked
                        )
                        update notification n
                           set state = 'DISPATCHING', next_attempt_at = :leaseUntil, lease_until = :leaseUntil
                          from due
                         where n.id = due.id
                        returning n.id, n.app_id, n.user_id, n.policy, n.fallback_payload::text as fallback_payload,
                                  n.due_at, n.fallback_attempts, due.previous_state, due.reclaimed, n.lease_until
                        """)
                .param("now", timestamp(now))
                .param("leaseUntil", timestamp(leaseUntil))
                .param("batch", batch)
                .query((rs, row) -> new ClaimedNotification(
                        rs.getObject("id", UUID.class),
                        rs.getString("app_id"),
                        rs.getString("user_id"),
                        FallbackPolicy.valueOf(rs.getString("policy")),
                        rs.getString("fallback_payload"),
                        instant(rs, "due_at"),
                        rs.getInt("fallback_attempts"),
                        NotificationState.valueOf(rs.getString("previous_state")),
                        rs.getBoolean("reclaimed"),
                        instant(rs, "lease_until")))
                .list();
    }

    /**
     * Fallback worker: records when to try again and releases the lease ({@code lease_until} cleared, so the next
     * claim is a normal one). Fenced on the caller's lease: only the worker still holding {@code claimedLeaseUntil}
     * (and the attempts count it claimed with) writes. If another worker re-claimed the row after this worker's lease
     * ran out, nothing is overwritten, whichever of the two finishes first.
     *
     * @return {@code false} if this worker no longer holds the lease (the caller drops its outcome)
     */
    public boolean reschedule(UUID id, Instant claimedLeaseUntil, int expectedAttempts, int attempts, Instant nextAttemptAt) {
        return jdbc.sql("""
                        update notification
                           set fallback_attempts = :attempts, next_attempt_at = :next, lease_until = null
                         where id = :id and state = 'DISPATCHING' and lease_until = :lease
                           and fallback_attempts = :expected
                        """)
                        .param("attempts", attempts)
                        .param("next", timestamp(nextAttemptAt))
                        .param("id", id)
                        .param("lease", timestamp(claimedLeaseUntil))
                        .param("expected", expectedAttempts)
                        .update()
                == 1;
    }

    /**
     * Fallback worker: closes a {@code DISPATCHING} row in a final fallback state ({@code FALLBACK_TRIGGERED},
     * {@code FALLBACK_FAILED} or {@code FALLBACK_EXPIRED}): {@code closed_at = at}, {@code due_at} and
     * {@code next_attempt_at} and {@code lease_until} cleared. Fenced on the caller's lease like {@link #reschedule}.
     *
     * @return {@code false} if this worker no longer holds the lease (the caller drops its outcome)
     */
    public boolean close(
            UUID id, Instant claimedLeaseUntil, int expectedAttempts, int attempts, NotificationState state, Instant at) {
        if (state != NotificationState.FALLBACK_TRIGGERED
                && state != NotificationState.FALLBACK_FAILED
                && state != NotificationState.FALLBACK_EXPIRED) {
            throw new IllegalArgumentException("not a final fallback state: " + state);
        }
        return jdbc.sql("""
                        update notification
                           set state = :state, fallback_attempts = :attempts, closed_at = :at,
                               due_at = null, next_attempt_at = null, lease_until = null
                         where id = :id and state = 'DISPATCHING' and lease_until = :lease
                           and fallback_attempts = :expected
                        """)
                        .param("state", state.name())
                        .param("attempts", attempts)
                        .param("at", timestamp(at))
                        .param("id", id)
                        .param("lease", timestamp(claimedLeaseUntil))
                        .param("expected", expectedAttempts)
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
