package io.reliablepush.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reliablepush.tracking.EventType;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The Prometheus metrics of spec 9.2, in one place. Micrometer names, exposed by the Prometheus registry as:
 * <ul>
 *   <li>{@code rp_notifications_registered_total}: notifications really inserted (replays of an {@code externalId} are not);</li>
 *   <li>{@code rp_events_total{type,late}}: event rows stored ({@code late=true} when the state transition no longer applied);</li>
 *   <li>{@code rp_fallback_total{outcome=triggered|failed|expired}}: fallback outcomes that were persisted;</li>
 *   <li>{@code rp_webhook_latency_seconds}: duration of every webhook call;</li>
 *   <li>{@code rp_due_backlog}: rows the fallback worker could claim now (worker role only, see {@link #registerDueBacklogGauge()}).</li>
 * </ul>
 * Tags are fixed enumerations, never ids: no appId, userId or trackingId. Every known tag combination is registered at
 * startup, so the series exist (at 0) before the first event.
 */
@Component
public class RpMetrics {

    static final String NOTIFICATIONS_REGISTERED = "rp.notifications.registered";
    static final String EVENTS = "rp.events";
    static final String FALLBACK = "rp.fallback";
    static final String WEBHOOK_LATENCY = "rp.webhook.latency";
    static final String DUE_BACKLOG = "rp.due.backlog";

    /** The values of the {@code outcome} tag of {@code rp_fallback_total}. */
    public static final List<String> FALLBACK_OUTCOMES = List.of("triggered", "failed", "expired");

    private final MeterRegistry registry;
    private final Counter registered;
    private final Map<EventType, Counter[]> events = new EnumMap<>(EventType.class);
    private final Map<String, Counter> fallback = new LinkedHashMap<>();
    private final Timer webhookLatency;
    private final AtomicLong dueBacklog = new AtomicLong();

    public RpMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.registered = Counter.builder(NOTIFICATIONS_REGISTERED)
                .description("Notifications registered (new rows only, not idempotent replays)")
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

    /** A notification was really inserted. */
    public void notificationRegistered() {
        registered.increment();
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
