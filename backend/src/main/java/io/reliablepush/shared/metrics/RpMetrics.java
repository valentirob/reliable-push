package io.reliablepush.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reliablepush.tracking.EventType;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The Prometheus metrics of spec 9.2, in one place. Micrometer names, exposed by the Prometheus registry as:
 * <ul>
 *   <li>{@code rp_notifications_registered_total{outcome=pending|no_device_registered}}: notifications really inserted
 *       (replays of a {@code trackingId} or {@code externalId} are not), by the state they were stored in;</li>
 *   <li>{@code rp_notification_registration_seconds}: server time of {@code POST /v1/notifications} (a histogram, so an
 *       installation can compare it with the sender's registration timeout);</li>
 *   <li>{@code rp_events_total{type,late}}: event rows stored ({@code late=true} when the state transition no longer applied);</li>
 *   <li>{@code rp_fallback_total{outcome=triggered|failed|expired}}: fallback outcomes that were persisted;</li>
 *   <li>{@code rp_fallback_suppressed_total{app,reason=circuit_open|fallback_suspended}}: fallbacks the circuit breaker
 *       closed without a call (worker role only, see {@link #registerFallbackSuppressed});</li>
 *   <li>{@code rp_fallback_circuit_transitions_total{app,transition=OPENED|CLOSED|FORCED|FORCE_ENDED}}: committed
 *       circuit transitions (both roles, see {@link #registerFallbackCircuitTransitions});</li>
 *   <li>{@code rp_fallback_circuit_check_failed_total}: worker circuit checks that failed, so the tick claimed nothing
 *       (worker role only, see {@link #registerFallbackCircuitCheckFailed});</li>
 *   <li>{@code rp_webhook_latency_seconds}: duration of every webhook call;</li>
 *   <li>{@code rp_due_backlog}: rows the fallback worker could claim now (worker role only, see {@link #registerDueBacklogGauge()}).</li>
 * </ul>
 * Tags are fixed enumerations, never ids: no userId or trackingId, and an app id only as the {@code app} tag of
 * {@code rp_fallback_suppressed_total} and {@code rp_fallback_circuit_transitions_total} (the configured apps, a
 * bounded set). Every known tag combination is registered
 * at startup, so the series exist (at 0) before the first event.
 */
@Component
public class RpMetrics {

    static final String NOTIFICATIONS_REGISTERED = "rp.notifications.registered";
    static final String NOTIFICATION_REGISTRATION = "rp.notification.registration";
    static final String EVENTS = "rp.events";
    static final String FALLBACK = "rp.fallback";
    static final String WEBHOOK_LATENCY = "rp.webhook.latency";
    static final String DUE_BACKLOG = "rp.due.backlog";
    static final String FALLBACK_SUPPRESSED = "rp.fallback.suppressed";
    static final String FALLBACK_CIRCUIT_TRANSITIONS = "rp.fallback.circuit.transitions";
    static final String FALLBACK_CIRCUIT_CHECK_FAILED = "rp.fallback.circuit.check.failed";

    /** The values of the {@code outcome} tag of {@code rp_notifications_registered_total}. */
    public static final List<String> REGISTRATION_OUTCOMES = List.of("pending", "no_device_registered");

    /**
     * The histogram buckets of {@code rp_notification_registration_seconds}, around the recommended 1 s sender
     * timeout (sidecar revision spec 1).
     */
    static final Duration[] REGISTRATION_BUCKETS = {
        Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
        Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(5)
    };

    /** The values of the {@code outcome} tag of {@code rp_fallback_total}. */
    public static final List<String> FALLBACK_OUTCOMES = List.of("triggered", "failed", "expired");

    /** The values of the {@code reason} tag of {@code rp_fallback_suppressed_total}. */
    public static final List<String> SUPPRESSION_REASONS = List.of("circuit_open", "fallback_suspended");

    /** The values of the {@code transition} tag of {@code rp_fallback_circuit_transitions_total}. */
    public static final List<String> CIRCUIT_TRANSITIONS = List.of("OPENED", "CLOSED", "FORCED", "FORCE_ENDED");

    private final MeterRegistry registry;
    private final Map<String, Counter> registered = new LinkedHashMap<>();
    private final Timer registration;
    private final Map<EventType, Counter[]> events = new EnumMap<>(EventType.class);
    private final Map<String, Counter> fallback = new LinkedHashMap<>();
    private final Timer webhookLatency;
    private final AtomicLong dueBacklog = new AtomicLong();

    public RpMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String outcome : REGISTRATION_OUTCOMES) {
            registered.put(outcome, Counter.builder(NOTIFICATIONS_REGISTERED)
                    .description("Notifications registered (new rows only, not idempotent replays), by stored state")
                    .tag("outcome", outcome)
                    .register(registry));
        }
        this.registration = Timer.builder(NOTIFICATION_REGISTRATION)
                .description("Server time of POST /v1/notifications")
                .serviceLevelObjectives(REGISTRATION_BUCKETS)
                .register(registry);
        for (EventType type : EventType.values()) {
            Counter onTime = eventCounter(type, false);
            Counter late = eventCounter(type, true);
            events.put(type, new Counter[] {onTime, late});
        }
        for (String outcome : FALLBACK_OUTCOMES) {
            fallback.put(outcome, Counter.builder(FALLBACK)
                    .description("Fallback outcomes persisted by the worker")
                    .tag("outcome", outcome)
                    .register(registry));
        }
        this.webhookLatency = Timer.builder(WEBHOOK_LATENCY)
                .description("Duration of fallback webhook calls")
                .register(registry);
    }

    private Counter eventCounter(EventType type, boolean late) {
        return Counter.builder(EVENTS)
                .description("Device events stored")
                .tag("type", type.name())
                .tag("late", Boolean.toString(late))
                .register(registry);
    }

    /**
     * A notification was really inserted.
     *
     * @param outcome {@code pending} or {@code no_device_registered}
     * @throws IllegalArgumentException for any other value (tags stay a closed set)
     */
    public void notificationRegistered(String outcome) {
        Counter counter = registered.get(outcome);
        if (counter == null) {
            throw new IllegalArgumentException("unknown registration outcome: " + outcome);
        }
        counter.increment();
    }

    /** The timer of {@code POST /v1/notifications} (untagged). */
    public Timer notificationRegistration() {
        return registration;
    }

    /** A device event row was stored; {@code late} when the notification could no longer make the transition. */
    public void event(EventType type, boolean late) {
        events.get(type)[late ? 1 : 0].increment();
    }

    /**
     * A fallback outcome was persisted.
     *
     * @param outcome {@code triggered}, {@code failed} or {@code expired}
     * @throws IllegalArgumentException for any other value (tags stay a closed set)
     */
    public void fallback(String outcome) {
        Counter counter = fallback.get(outcome);
        if (counter == null) {
            throw new IllegalArgumentException("unknown fallback outcome: " + outcome);
        }
        counter.increment();
    }

    /**
     * Registers {@code rp_fallback_suppressed_total} at 0 for every configured app and reason. Called by the worker
     * role only (the suppressor), so an api-only replica does not expose it.
     */
    public void registerFallbackSuppressed(Collection<String> appIds) {
        appIds.forEach(app -> SUPPRESSION_REASONS.forEach(reason -> suppressedCounter(app, reason)));
    }

    /**
     * {@code count} fallbacks of {@code appId} were closed as {@code FALLBACK_EXPIRED} without a call by the circuit
     * breaker: counted in {@code rp_fallback_suppressed_total{app,reason}} and in
     * {@code rp_fallback_total{outcome="expired"}}.
     *
     * @param reason {@code circuit_open} or {@code fallback_suspended}
     * @throws IllegalArgumentException for any other reason (tags stay a closed set)
     */
    public void fallbackSuppressed(String appId, String reason, long count) {
        if (!SUPPRESSION_REASONS.contains(reason)) {
            throw new IllegalArgumentException("unknown suppression reason: " + reason);
        }
        suppressedCounter(appId, reason).increment(count);
        fallback.get("expired").increment(count);
    }

    private Counter suppressedCounter(String appId, String reason) {
        return Counter.builder(FALLBACK_SUPPRESSED)
                .description("Fallbacks closed as expired without a call by the circuit breaker")
                .tag("app", appId)
                .tag("reason", reason)
                .register(registry);
    }

    /**
     * Registers {@code rp_fallback_circuit_transitions_total} at 0 for every configured app and transition. Called by
     * the circuit breaker, which both roles have (the operator resume runs in the API role).
     */
    public void registerFallbackCircuitTransitions(Collection<String> appIds) {
        appIds.forEach(app -> CIRCUIT_TRANSITIONS.forEach(transition -> transitionCounter(app, transition)));
    }

    /**
     * A circuit transition of {@code appId} was committed (once per stored {@code fallback_circuit_event}).
     *
     * @param transition {@code OPENED}, {@code CLOSED}, {@code FORCED} or {@code FORCE_ENDED}
     * @throws IllegalArgumentException for any other value (tags stay a closed set)
     */
    public void fallbackCircuitTransition(String appId, String transition) {
        if (!CIRCUIT_TRANSITIONS.contains(transition)) {
            throw new IllegalArgumentException("unknown circuit transition: " + transition);
        }
        transitionCounter(appId, transition).increment();
    }

    private Counter transitionCounter(String appId, String transition) {
        return Counter.builder(FALLBACK_CIRCUIT_TRANSITIONS)
                .description("Fallback circuit transitions committed")
                .tag("app", appId)
                .tag("transition", transition)
                .register(registry);
    }

    /**
     * Registers {@code rp_fallback_circuit_check_failed_total} at 0. Called by the worker role only (the fallback
     * scheduler), so an api-only replica does not expose a check it never runs.
     */
    public void registerFallbackCircuitCheckFailed() {
        checkFailedCounter();
    }

    /** A worker tick's circuit check (evaluation or suppression) failed: the tick claimed nothing (fail closed). */
    public void fallbackCircuitCheckFailed() {
        checkFailedCounter().increment();
    }

    private Counter checkFailedCounter() {
        return Counter.builder(FALLBACK_CIRCUIT_CHECK_FAILED)
                .description("Fallback circuit checks that failed (the tick claimed nothing)")
                .register(registry);
    }

    /** The timer of webhook calls (untagged). */
    public Timer webhookLatency() {
        return webhookLatency;
    }

    /**
     * Registers the {@code rp_due_backlog} gauge. Called by the worker role only, so an api-only replica does not
     * expose a backlog it never measures. The gauge reads a cached value: a scrape never queries the database.
     */
    public void registerDueBacklogGauge() {
        Gauge.builder(DUE_BACKLOG, dueBacklog, AtomicLong::get)
                .description("Notifications due for the fallback worker (claimable now)")
                .register(registry);
    }

    /** The latest measured backlog, shown by the next scrape. */
    public void dueBacklog(long rows) {
        dueBacklog.set(rows);
    }
}
