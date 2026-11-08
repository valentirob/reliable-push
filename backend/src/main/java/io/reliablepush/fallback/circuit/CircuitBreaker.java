package io.reliablepush.fallback.circuit;

import io.reliablepush.fallback.circuit.CircuitTransitions.Transition;
import io.reliablepush.shared.logging.EventLog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The per-app fallback circuit detector (spec 4). {@link #evaluate} runs at the start of every worker tick, in one
 * transaction holding {@code pg_try_advisory_xact_lock(4242002)}: the worker that gets the lock observes every app and
 * writes its transitions; the others only read the stored states. The lock holder also deletes, once a day per
 * instance, the circuit events older than {@code admin.rollup-retention-days} (spec 3), also while the breaker is
 * disabled.
 *
 * <p>Time comes from the application {@link Clock}, the one the claim and the tracking writes use, so detection and
 * claim agree on {@code now} (also with the fast clock of the demo and E2E).
 *
 * <p>Not profile-restricted: the API role uses {@link #force} for the operator resume. Log lines are written after the
 * transaction commits, so a rolled-back transition is never logged.
 */
@Component
public class CircuitBreaker {

    /** Same constant on every instance: the transaction-scoped advisory lock of the detector. */
    static final long LOCK_KEY = 4_242_002L;

    /** How often (per instance) the lock holder deletes old circuit events. */
    static final Duration PRUNE_INTERVAL = Duration.ofDays(1);

    private final CircuitRepository circuits;
    private final DetectionQueries queries;
    private final CircuitBreakerProperties properties;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration retention;

    /** Application-clock time of the last prune on this instance; {@code null} until the first one. */
    private volatile Instant lastPrune;

    /** A written transition: the event as stored, and the detector's raw ratio (logged unrounded) for an open. */
    private record Applied(Circuit current, Transition transition, CircuitEvent event, Double ratio) {}

    private record Evaluation(Map<String, Circuit> circuits, List<Applied> applied, Instant prunedAt) {}

    CircuitBreaker(
            CircuitRepository circuits,
            DetectionQueries queries,
            CircuitBreakerProperties properties,
            TransactionTemplate transactions,
            JdbcClient jdbc,
            Clock clock,
            @Value("${reliable-push.admin.rollup-retention-days:400}") int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalStateException("reliable-push.admin.rollup-retention-days must be at least 1");
        }
        this.circuits = circuits;
        this.queries = queries;
        this.properties = properties;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays);
    }

    /**
     * Evaluates the circuits of {@code appIds} and returns every one of them (an app without a stored row as CLOSED
     * since the epoch). With {@code circuit-breaker.enabled: false} every app is CLOSED and no circuit is read or
     * written; only the daily prune of old events runs (under the same lock).
     */
    public Map<String, Circuit> evaluate(Collection<String> appIds) {
        Set<String> apps = new LinkedHashSet<>(appIds);
        if (!properties.enabled()) {
            pruneOnly();
            Map<String, Circuit> closed = new LinkedHashMap<>();
            apps.forEach(app -> closed.put(app, Circuit.closed(app, Instant.EPOCH)));
            return closed;
        }
        Evaluation evaluation = transactions.execute(status -> tryLock() ? evaluateLocked(apps) : null);
        if (evaluation == null) {
            return stored(apps);
        }
        if (evaluation.prunedAt() != null) {
            lastPrune = evaluation.prunedAt();
        }
        evaluation.applied().forEach(CircuitBreaker::log);
        return evaluation.circuits();
    }

    /**
     * The operator resume (spec 6): the app's circuit becomes FORCED until {@code now + force-hold}, whatever its
     * state, with event {@code FORCED}/{@code forced} and the admin key id. Waits for an evaluation in progress (same
     * advisory lock, blocking), so a detector transition never overwrites it. Forcing an already FORCED circuit
     * restarts the hold ({@code forced_until = now + force-hold}) and writes a new {@code FORCED} event. The caller
     * checks {@code suspended} and {@code enabled} first.
     */
    public Circuit force(String appId, String adminKeyId) {
        Applied applied = transactions.execute(status -> {
            jdbc.sql("select 1 from pg_advisory_xact_lock(:key)").param("key", LOCK_KEY).query(Integer.class).single();
            Instant now = now();
            Circuit current = circuits.find(appId).orElse(Circuit.closed(appId, Instant.EPOCH));
            return apply(current, CircuitTransitions.force(current, now, adminKeyId, properties), null, now,
                    adminKeyId);
        });
        log(applied);
        return applied.transition().next();
    }

    private boolean tryLock() {
        Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:key)")
                .param("key", LOCK_KEY)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(locked);
    }

    /** The breaker is disabled: no detection, but old events are still deleted once a day. */
    private void pruneOnly() {
        Instant now = now();
        if (!pruneDue(now)) {
            return; // no database round trip on the ticks between two prunes
        }
        Instant prunedAt = transactions.execute(status -> tryLock() && pruneIfDue(now) ? now : null);
        if (prunedAt != null) {
            lastPrune = prunedAt;
        }
    }

    private Evaluation evaluateLocked(Set<String> apps) {
        Instant now = now();
        Map<String, Circuit> stored = circuits.findAll(apps);
        Map<String, Circuit> result = new LinkedHashMap<>();
        List<Applied> applied = new ArrayList<>();
        for (String app : apps) {
            Circuit current = stored.getOrDefault(app, Circuit.closed(app, Instant.EPOCH));
            Observation obs = observe(current, now);
            Optional<Transition> transition = CircuitTransitions.next(current, obs, now, properties);
            if (transition.isPresent()) {
                Applied done = apply(current, transition.get(), obs, now, null);
                applied.add(done);
                result.put(app, done.transition().next());
            } else {
                result.put(app, current);
            }
        }
        Instant prunedAt = pruneIfDue(now) ? now : null;
        return new Evaluation(result, applied, prunedAt);
    }

    /** Reads only what the circuit's state needs (spec 4); a quiet CLOSED app costs the two due-set index reads. */
    private Observation observe(Circuit current, Instant now) {
        String app = current.appId();
        return switch (current.state()) {
            case CLOSED -> {
                long due = queries.due(app, now, properties.window(), properties.minDue());
                Double ratio = due >= properties.minDue()
                        ? queries.cohortRatio(app, now, properties.window(), properties.minDue())
                        : null;
                yield new Observation(due, ratio, false);
            }
            case OPEN -> {
                boolean minOpenOver = !now.isBefore(current.since().plus(properties.minOpen()));
                yield new Observation(0, null, minOpenOver && resumed(current));
            }
            case FORCED -> {
                boolean expired = current.forcedUntil() == null || !now.isBefore(current.forcedUntil());
                yield new Observation(0, null, !expired && resumed(current));
            }
        };
    }

    private boolean resumed(Circuit current) {
        return queries.resumed(current.appId(), current.since(), properties.closeMinDelivered());
    }

    private Applied apply(Circuit current, Transition t, Observation obs, Instant now, String adminKeyId) {
        boolean opened = CircuitTransitions.OPENED.equals(t.transition());
        CircuitEvent event = new CircuitEvent(
                0,
                current.appId(),
                now,
                t.transition(),
                t.reason(),
                opened ? obs.due() : null,
                opened ? BigDecimal.valueOf(obs.ratio()).setScale(4, RoundingMode.HALF_UP) : null,
                opened ? null : current.suppressed(), // replaced by the stored count inside the write
                adminKeyId);
        CircuitEvent written = circuits.transition(t.next(), event);
        return new Applied(current, t, written, opened ? obs.ratio() : null);
    }

    private boolean pruneDue(Instant now) {
        Instant last = lastPrune;
        return last == null || Duration.between(last, now).compareTo(PRUNE_INTERVAL) >= 0;
    }

    private boolean pruneIfDue(Instant now) {
        if (!pruneDue(now)) {
            return false;
        }
        circuits.deleteEventsBefore(now.minus(retention));
        return true;
    }

    private Map<String, Circuit> stored(Set<String> apps) {
        Map<String, Circuit> stored = circuits.findAll(apps);
        Map<String, Circuit> result = new LinkedHashMap<>();
        apps.forEach(app -> result.put(app, stored.getOrDefault(app, Circuit.closed(app, Instant.EPOCH))));
        return result;
    }

    /** Microseconds, the precision PostgreSQL stores (as the claim does). */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void log(Applied applied) {
        CircuitEvent e = applied.event();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rp.app_id", e.appId());
        switch (e.transition()) {
            case CircuitTransitions.OPENED -> {
                fields.put("due", e.due());
                fields.put("ratio", applied.ratio()); // the event column keeps 4 decimals
                EventLog.warn("fallback.circuit_opened", fields);
            }
            case CircuitTransitions.FORCED -> {
                fields.put("rp.admin_key", e.adminKeyId());
                fields.put("suppressed", e.suppressed());
                EventLog.warn("fallback.circuit_forced", fields);
            }
            default -> {
                fields.put("reason", e.reason());
                fields.put("suppressed", e.suppressed());
                EventLog.info("fallback.circuit_closed", fields);
            }
        }
    }
}
