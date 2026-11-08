package io.reliablepush.fallback.circuit;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.metrics.RpMetrics;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Suppression (spec 5), run by the worker tick after {@link CircuitBreaker#evaluate} and before any claim. With
 * {@code suspended} every app is suppressed, whatever its circuit (also {@code FORCED}); otherwise each app whose
 * circuit is {@code OPEN}. Suppressing an app closes its claimable rows as {@code FALLBACK_EXPIRED} without a webhook
 * call, in chunks of {@value #CHUNK} rows, each chunk and its {@code fallback_circuit.suppressed} increment one
 * transaction; rows under a live lease are left to their worker. For reason {@code circuit_open} each chunk first locks
 * the app's circuit row and closes nothing once it is no longer {@code OPEN} (an operator resume or a close committed
 * during a long pass stops it). Per app with at least one row closed: one WARN
 * {@code fallback.suppressed} ({@code rp.app_id}, {@code count}, {@code reason}), never one line per notification, and
 * the metrics {@code rp_fallback_suppressed_total} and {@code rp_fallback_total{outcome="expired"}}.
 */
@Component
@Profile("worker")
public class Suppressor {

    /** Rows closed per statement (and transaction). */
    static final int CHUNK = 1000;

    static final String CIRCUIT_OPEN = "circuit_open";
    static final String FALLBACK_SUSPENDED = "fallback_suspended";

    private final NotificationRepository notifications;
    private final CircuitRepository circuits;
    private final RpMetrics metrics;
    private final TransactionTemplate transactions;

    Suppressor(NotificationRepository notifications, CircuitRepository circuits, RpMetrics metrics,
            TransactionTemplate transactions, AppRegistry apps) {
        this.notifications = notifications;
        this.circuits = circuits;
        this.metrics = metrics;
        this.transactions = transactions;
        metrics.registerFallbackSuppressed(apps.all().stream().map(AppConfig::appId).toList());
    }

    /**
     * Suppresses the apps of {@code circuits} that must not fire now.
     *
     * @param circuits the evaluated circuit of every configured app
     * @param suspended {@code fallback.suspended}: every app is suppressed
     * @param now the tick's time (application clock); rows due up to it are closed with {@code closed_at = now}
     * @return the suppressed app ids, whether or not they had rows to close: the claim that follows skips them
     * @throws RuntimeException when a chunk fails (the chunks before it stay committed and logged); the caller then
     *     claims nothing this tick
     */
    public Set<String> suppress(Map<String, Circuit> circuits, boolean suspended, Instant now) {
        Set<String> suppressed = new LinkedHashSet<>();
        for (Circuit circuit : circuits.values()) {
            if (suspended || circuit.state() == CircuitState.OPEN) {
                suppressed.add(circuit.appId());
            }
        }
        String reason = suspended ? FALLBACK_SUSPENDED : CIRCUIT_OPEN;
        suppressed.forEach(app -> expire(app, reason, now));
        return suppressed;
    }

    private void expire(String app, String reason, Instant now) {
        long total = 0;
        try {
            int closed;
            do {
                closed = transactions.execute(status -> {
                    // an operator resume or a close committed since the evaluation ends the pass: the circuit row
                    // stays locked until this chunk commits, so the state cannot change under it
                    if (CIRCUIT_OPEN.equals(reason) && circuits.lockState(app) != CircuitState.OPEN) {
                        return 0;
                    }
                    int n = notifications.expireClaimable(app, now, CHUNK);
                    if (n > 0) {
                        circuits.addSuppressed(app, n, now);
                    }
                    return n;
                });
                if (closed > 0) {
                    metrics.fallbackSuppressed(app, reason, closed);
                }
                total += closed;
            } while (closed == CHUNK); // a short chunk ends it (SKIP LOCKED may skip rows: the next tick takes them)
        } finally {
            if (total > 0) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("rp.app_id", app);
                fields.put("count", total);
                fields.put("reason", reason);
                EventLog.warn("fallback.suppressed", fields);
            }
        }
    }
}
