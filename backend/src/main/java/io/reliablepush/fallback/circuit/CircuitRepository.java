package io.reliablepush.fallback.circuit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** SQL of the fallback circuit state and its event history (spec 3). Apps without a row read as CLOSED. */
@Component
public class CircuitRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    CircuitRepository(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** The stored circuits among {@code appIds}; an app absent from the map is CLOSED. */
    public Map<String, Circuit> findAll(Collection<String> appIds) {
        Map<String, Circuit> result = new HashMap<>();
        if (appIds.isEmpty()) {
            return result;
        }
        jdbc.sql("select * from fallback_circuit where app_id = any (cast(:apps as text[]))")
                .param("apps", appIds.toArray(String[]::new))
                .query(CircuitRepository::circuit)
                .list()
                .forEach(c -> result.put(c.appId(), c));
        return result;
    }

    public Optional<Circuit> find(String appId) {
        return jdbc.sql("select * from fallback_circuit where app_id = :app")
                .param("app", appId)
                .query(CircuitRepository::circuit)
                .optional();
    }

    /**
     * Writes the circuit row and appends the event, in one transaction (joins the caller's, if any). The event's
     * {@code suppressed} (when not {@code null}: every transition but {@code OPENED}) is the count stored for the
     * finished period, read under the row lock inside this write, not the caller's snapshot: a suppressor that added to
     * it after the detector read the circuit is not lost, and one that adds later waits for the lock and counts in the
     * new period (which starts at {@code next.suppressed()}).
     *
     * @return the event as written (with the stored count)
     * @throws IllegalArgumentException when the event belongs to another app than the circuit
     */
    public CircuitEvent transition(Circuit next, CircuitEvent event) {
        if (!next.appId().equals(event.appId())) {
            throw new IllegalArgumentException("circuit and event must belong to the same app");
        }
        return transactions.execute(status -> write(next, event));
    }

    private CircuitEvent write(Circuit next, CircuitEvent event) {
        // the row exists (or waits for a concurrent insert to commit), then is locked before its count is read
        jdbc.sql("""
                        insert into fallback_circuit (app_id, state, since, suppressed)
                        values (:app, 'CLOSED', :since, 0)
                        on conflict (app_id) do nothing
                        """)
                .param("app", next.appId())
                .param("since", Timestamp.from(next.since()))
                .update();
        long finished = jdbc.sql("select suppressed from fallback_circuit where app_id = :app for update")
                .param("app", next.appId())
                .query(Long.class)
                .single();
        jdbc.sql("""
                        update fallback_circuit
                           set state = :state, since = :since, forced_until = :until, forced_by = :by,
                               suppressed = :suppressed
                         where app_id = :app
                        """)
                .param("app", next.appId())
                .param("state", next.state().name())
                .param("since", Timestamp.from(next.since()))
                .param("until", next.forcedUntil() == null ? null : Timestamp.from(next.forcedUntil()))
                .param("by", next.forcedBy())
                .param("suppressed", next.suppressed())
                .update();
        CircuitEvent written = new CircuitEvent(event.id(), event.appId(), event.at(), event.transition(),
                event.reason(), event.due(), event.ratio(), event.suppressed() == null ? null : finished,
                event.adminKeyId());
        jdbc.sql("""
                        insert into fallback_circuit_event
                            (app_id, at, transition, reason, due, ratio, suppressed, admin_key_id)
                        values (:app, :at, :transition, :reason, :due, :ratio, :suppressed, :by)
                        """)
                .param("app", written.appId())
                .param("at", Timestamp.from(written.at()))
                .param("transition", written.transition())
                .param("reason", written.reason())
                .param("due", written.due())
                .param("ratio", written.ratio())
                .param("suppressed", written.suppressed())
                .param("by", written.adminKeyId())
                .update();
        return written;
    }

    /**
     * Adds {@code count} to the app's suppressed counter. An app without a row (suppressed because
     * {@code fallback.suspended} is {@code true}) gets a {@code CLOSED} row with {@code since = now}, so the count is
     * kept. Joins the caller's transaction (the suppressor's chunk).
     */
    public void addSuppressed(String appId, long count, Instant now) {
        jdbc.sql("""
                        insert into fallback_circuit (app_id, state, since, suppressed)
                        values (:app, 'CLOSED', :now, :count)
                        on conflict (app_id) do update set suppressed = fallback_circuit.suppressed + excluded.suppressed
                        """)
                .param("app", appId)
                .param("now", Timestamp.from(now))
                .param("count", count)
                .update();
    }

    /** The history of one app, newest first; {@code beforeId} (an event id) continues after that event. */
    public List<CircuitEvent> events(String appId, int limit, Long beforeId) {
        return jdbc.sql("""
                        select * from fallback_circuit_event
                        where app_id = :app and (cast(:before as bigint) is null or id < :before)
                        order by id desc
                        limit :limit
                        """)
                .param("app", appId)
                .param("before", beforeId)
                .param("limit", limit)
                .query(CircuitRepository::event)
                .list();
    }

    /** Retention: deletes the events strictly older than {@code cutoff}. */
    public int deleteEventsBefore(Instant cutoff) {
        return jdbc.sql("delete from fallback_circuit_event where at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    private static Circuit circuit(ResultSet rs, int n) throws SQLException {
        Timestamp until = rs.getTimestamp("forced_until");
        return new Circuit(
                rs.getString("app_id"),
                CircuitState.valueOf(rs.getString("state")),
                rs.getTimestamp("since").toInstant(),
                until == null ? null : until.toInstant(),
                rs.getString("forced_by"),
                rs.getLong("suppressed"));
    }

    private static CircuitEvent event(ResultSet rs, int n) throws SQLException {
        return new CircuitEvent(
                rs.getLong("id"),
                rs.getString("app_id"),
                rs.getTimestamp("at").toInstant(),
                rs.getString("transition"),
                rs.getString("reason"),
                nullableLong(rs, "due"),
                rs.getBigDecimal("ratio"),
                nullableLong(rs, "suppressed"),
                rs.getString("admin_key_id"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
