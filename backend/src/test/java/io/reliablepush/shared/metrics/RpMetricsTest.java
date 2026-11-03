package io.reliablepush.shared.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.reliablepush.tracking.EventType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RpMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final RpMetrics metrics = new RpMetrics(registry);

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    @Test
    void everyKnownTagCombinationExistsAtZeroFromTheStart() {
        assertThat(counter("rp.notifications.registered")).isZero();
        for (String type : new String[] {"DELIVERED", "READ"}) {
            for (String late : new String[] {"true", "false"}) {
                assertThat(counter("rp.events", "type", type, "late", late)).isZero();
            }
        }
        for (String outcome : new String[] {"triggered", "failed", "expired"}) {
            assertThat(counter("rp.fallback", "outcome", outcome)).isZero();
        }
        assertThat(registry.get("rp.webhook.latency").timer().count()).isZero();
    }

    @Test
    void countsPerTagCombination() {
        metrics.notificationRegistered();
        metrics.event(EventType.DELIVERED, false);
        metrics.event(EventType.READ, true);
        metrics.event(EventType.READ, true);
        metrics.fallback("triggered");
        metrics.fallback("expired");
        metrics.fallback("expired");
        metrics.webhookLatency().record(Duration.ofMillis(5));

        assertThat(counter("rp.notifications.registered")).isEqualTo(1);
        assertThat(counter("rp.events", "type", "DELIVERED", "late", "false")).isEqualTo(1);
        assertThat(counter("rp.events", "type", "DELIVERED", "late", "true")).isZero();
        assertThat(counter("rp.events", "type", "READ", "late", "true")).isEqualTo(2);
        assertThat(counter("rp.fallback", "outcome", "triggered")).isEqualTo(1);
        assertThat(counter("rp.fallback", "outcome", "failed")).isZero();
        assertThat(counter("rp.fallback", "outcome", "expired")).isEqualTo(2);
        assertThat(registry.get("rp.webhook.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void unknownFallbackOutcomeIsRejected() {
        assertThatThrownBy(() -> metrics.fallback("retry_scheduled")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dueBacklogGaugeExistsOnlyOnceTheWorkerRegistersIt() {
        assertThat(registry.find("rp.due.backlog").gauge()).isNull();

        metrics.registerDueBacklogGauge();
        metrics.dueBacklog(7);

        assertThat(registry.get("rp.due.backlog").gauge().value()).isEqualTo(7.0);
    }
}
