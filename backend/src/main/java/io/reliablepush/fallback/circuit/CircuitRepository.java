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

    /** Upserts the circuit row and appends the event, in one transaction (joins the caller's, if any). */
    public void transition(Circuit next, CircuitEvent event) {
        transactions.executeWithoutResult(status -> write(next, event));
    }

    private void write(Circuit next, CircuitEvent event) {
        jdbc.sql("""
                        insert into fallback_circuit (app_id, state, since, forced_until, forced_by, suppressed)
                        values (:app, :state, :since, :until, :by, :suppressed)
                        on conflict (app_id) do update set
                            state = excluded.state, since = excluded.since, forced_until = excluded.forced_until,
                            forced_by = excluded.forced_by, suppressed = excluded.suppressed
                        """)
                .param("app", next.appId())
                .param("state", next.state().name())
                .param("since", Timestamp.from(next.since()))
                .param("until", next.forcedUntil() == null ? null : Timestamp.from(next.forcedUntil()))
                .param("by", next.forcedBy())
                .param("suppressed", next.suppressed())
                .update();
        jdbc.sql("""
                        insert into fallback_circuit_event
                            (app_id, at, transition, reason, due, ratio, suppressed, admin_key_id)
                        values (:app, :at, :transition, :reason, :due, :ratio, :suppressed, :by)
                        """)
                .param("app", event.appId())
                .param("at", Timestamp.from(event.at()))
                .param("transition", event.transition())
                .param("reason", event.reason())
                .param("due", event.due())
                .param("ratio", event.ratio())
                .param("suppressed", event.suppressed())
                .param("by", event.adminKeyId())
                .update();
    }

    /** Adds to the suppressed counter of an existing circuit; an app without a row is left alone. */
    public void addSuppressed(String appId, long count) {
        jdbc.sql("update fallback_circuit set suppressed = suppressed + :count where app_id = :app")
                .param("count", count)
                .param("app", appId)
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
