package io.reliablepush.shared.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.reliablepush.tracking.EventType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RpMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final RpMetrics metrics = new RpMetrics(registry);

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    @Test
    void everyKnownTagCombinationExistsAtZeroFromTheStart() {
        assertThat(counter("rp.notifications.registered", "outcome", "pending")).isZero();
        assertThat(counter("rp.notifications.registered", "outcome", "no_device_registered")).isZero();
        assertThat(registry.get("rp.notification.registration").timer().count()).isZero();
        for (String type : new String[] {"DELIVERED", "READ"}) {
            for (String late : new String[] {"true", "false"}) {
                for (String token : new String[] {"valid", "grace"}) {
                    assertThat(counter("rp.events", "type", type, "late", late, "token", token)).isZero();
                }
            }
        }
        for (String outcome : new String[] {"triggered", "failed", "expired"}) {
            assertThat(counter("rp.fallback", "outcome", outcome)).isZero();
        }
        assertThat(registry.get("rp.webhook.latency").timer().count()).isZero();
    }

    @Test
    void countsPerTagCombination() {
        metrics.notificationRegistered("pending");
        metrics.notificationRegistered("no_device_registered");
        metrics.notificationRegistered("no_device_registered");
        metrics.notificationRegistration().record(Duration.ofMillis(3));
        metrics.event(EventType.DELIVERED, false, false);
        metrics.event(EventType.READ, true, false);
        metrics.event(EventType.READ, true, false);
        metrics.event(EventType.READ, false, true);
        metrics.fallback("triggered");
        metrics.fallback("expired");
        metrics.fallback("expired");
        metrics.webhookLatency().record(Duration.ofMillis(5));

        assertThat(counter("rp.notifications.registered", "outcome", "pending")).isEqualTo(1);
        assertThat(counter("rp.notifications.registered", "outcome", "no_device_registered")).isEqualTo(2);
        assertThat(registry.get("rp.notification.registration").timer().count()).isEqualTo(1);
        assertThat(counter("rp.events", "type", "DELIVERED", "late", "false", "token", "valid")).isEqualTo(1);
        assertThat(counter("rp.events", "type", "DELIVERED", "late", "true", "token", "valid")).isZero();
        assertThat(counter("rp.events", "type", "READ", "late", "true", "token", "valid")).isEqualTo(2);
        assertThat(counter("rp.events", "type", "READ", "late", "false", "token", "grace")).isEqualTo(1);
        assertThat(counter("rp.fallback", "outcome", "triggered")).isEqualTo(1);
        assertThat(counter("rp.fallback", "outcome", "failed")).isZero();
        assertThat(counter("rp.fallback", "outcome", "expired")).isEqualTo(2);
        assertThat(registry.get("rp.webhook.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void unknownRegistrationOutcomeIsRejected() {
        assertThatThrownBy(() -> metrics.notificationRegistered("PENDING")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownFallbackOutcomeIsRejected() {
        assertThatThrownBy(() -> metrics.fallback("retry_scheduled")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void circuitTransitionsAreRegisteredAtZeroForEveryConfiguredAppAndTransition() {
        metrics.registerFallbackCircuitTransitions(List.of("shop", "news"));

        for (String app : List.of("shop", "news")) {
            for (String transition : List.of("OPENED", "CLOSED", "FORCED", "FORCE_ENDED")) {
                assertThat(counter("rp.fallback.circuit.transitions", "app", app, "transition", transition)).isZero();
            }
        }
    }

    @Test
    void countsCircuitTransitionsPerAppAndTransition() {
        metrics.registerFallbackCircuitTransitions(List.of("shop"));

        metrics.fallbackCircuitTransition("shop", "OPENED");
        metrics.fallbackCircuitTransition("shop", "FORCED");
        metrics.fallbackCircuitTransition("shop", "FORCED");

        assertThat(counter("rp.fallback.circuit.transitions", "app", "shop", "transition", "OPENED")).isEqualTo(1);
        assertThat(counter("rp.fallback.circuit.transitions", "app", "shop", "transition", "FORCED")).isEqualTo(2);
        assertThat(counter("rp.fallback.circuit.transitions", "app", "shop", "transition", "CLOSED")).isZero();
        assertThatThrownBy(() -> metrics.fallbackCircuitTransition("shop", "HALF_OPEN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void circuitCheckFailuresExistOnlyOnceTheWorkerRegistersThem() {
        assertThat(registry.find("rp.fallback.circuit.check.failed").counter()).isNull();

        metrics.registerFallbackCircuitCheckFailed();
        assertThat(counter("rp.fallback.circuit.check.failed")).isZero();
        metrics.fallbackCircuitCheckFailed();
        metrics.fallbackCircuitCheckFailed();

        assertThat(counter("rp.fallback.circuit.check.failed")).isEqualTo(2);
    }

    @Test
    void dueBacklogGaugeExistsOnlyOnceTheWorkerRegistersIt() {
        assertThat(registry.find("rp.due.backlog").gauge()).isNull();

        metrics.registerDueBacklogGauge();
        metrics.dueBacklog(7);

        assertThat(registry.get("rp.due.backlog").gauge().value()).isEqualTo(7.0);
    }
}
