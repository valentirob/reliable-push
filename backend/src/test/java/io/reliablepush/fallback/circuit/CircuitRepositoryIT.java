package io.reliablepush.fallback.circuit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.support.PostgresTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** The circuit state row and its event history against a real PostgreSQL (spec 3). */
@ActiveProfiles({"api", "worker", "test"})
@TestPropertySource(properties = "reliable-push.worker.poll-interval=1d")
class CircuitRepositoryIT extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");

    @Autowired
    CircuitRepository circuits;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from fallback_circuit").update();
        jdbc.sql("delete from fallback_circuit_event").update();
    }

    private static CircuitEvent event(String app, Instant at, String transition, String reason) {
        return new CircuitEvent(0, app, at, transition, reason, 60L, new BigDecimal("0.0500"), null, null);
    }

    @Test
    void missingRowReadsClosed() {
        assertThat(circuits.find("shop")).isEmpty();
        Map<String, Circuit> all = circuits.findAll(List.of("shop", "other"));
        assertThat(all).isEmpty();
        assertThat(Circuit.closed("shop", T0))
                .isEqualTo(new Circuit("shop", CircuitState.CLOSED, T0, null, null, 0));
    }

    @Test
    void transitionUpsertsAndAppendsEvent() {
        Instant t1 = T0.plusSeconds(60);
        circuits.transition(new Circuit("shop", CircuitState.OPEN, T0, null, null, 0),
                event("shop", T0, "OPENED", "tracking_missing"));
        assertThat(circuits.find("shop")).hasValueSatisfying(c -> {
            assertThat(c.state()).isEqualTo(CircuitState.OPEN);
            assertThat(c.since()).isEqualTo(T0);
        });

        circuits.transition(new Circuit("shop", CircuitState.CLOSED, t1, null, null, 0),
                new CircuitEvent(0, "shop", t1, "CLOSED", "delivered_resumed", null, null, 42L, null));

        Circuit now = circuits.find("shop").orElseThrow();
        assertThat(now.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(now.since()).isEqualTo(t1);
        assertThat(jdbc.sql("select count(*) from fallback_circuit").query(Long.class).single()).isEqualTo(1);

        List<CircuitEvent> events = circuits.events("shop", 10, null);
        assertThat(events).extracting(CircuitEvent::transition).containsExactly("CLOSED", "OPENED");
        assertThat(events.get(0).suppressed()).isEqualTo(42L);
        assertThat(events.get(0).due()).isNull();
        assertThat(events.get(0).at()).isEqualTo(t1);
        assertThat(events.get(1).due()).isEqualTo(60L);
        assertThat(events.get(1).ratio()).isEqualByComparingTo("0.05");
        assertThat(events.get(0).id()).isGreaterThan(events.get(1).id());
        assertThat(circuits.findAll(List.of("shop", "other"))).containsOnlyKeys("shop");
    }

    @Test
    void transitionRejectsAnEventOfAnotherApp() {
        assertThatThrownBy(() -> circuits.transition(new Circuit("shop", CircuitState.OPEN, T0, null, null, 0),
                        event("other", T0, "OPENED", "tracking_missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app");
        assertThat(circuits.find("shop")).isEmpty();
        assertThat(circuits.events("other", 10, null)).isEmpty();
    }

    @Test
    void forcedStateKeepsItsOperator() {
        Instant until = T0.plusSeconds(3600);
        circuits.transition(new Circuit("shop", CircuitState.FORCED, T0, until, "ops-1", 0),
                new CircuitEvent(0, "shop", T0, "FORCED", "forced", null, null, 5L, "ops-1"));
        Circuit c = circuits.find("shop").orElseThrow();
        assertThat(c.forcedUntil()).isEqualTo(until);
        assertThat(c.forcedBy()).isEqualTo("ops-1");
        assertThat(circuits.events("shop", 1, null).get(0).adminKeyId()).isEqualTo("ops-1");
    }

    @Test
    void addSuppressedAccumulates() {
        circuits.transition(new Circuit("shop", CircuitState.OPEN, T0, null, null, 0),
                event("shop", T0, "OPENED", "tracking_missing"));
        circuits.addSuppressed("shop", 100);
        circuits.addSuppressed("shop", 23);
        assertThat(circuits.find("shop").orElseThrow().suppressed()).isEqualTo(123);
        circuits.addSuppressed("ghost", 5);
        assertThat(circuits.find("ghost")).isEmpty();
    }

    @Test
    void eventsPageByCursor() {
        circuits.transition(new Circuit("shop", CircuitState.OPEN, T0, null, null, 0),
                event("shop", T0, "OPENED", "tracking_missing"));
        circuits.transition(new Circuit("shop", CircuitState.CLOSED, T0.plusSeconds(1), null, null, 0),
                event("shop", T0.plusSeconds(1), "CLOSED", "delivered_resumed"));
        circuits.transition(new Circuit("shop", CircuitState.OPEN, T0.plusSeconds(2), null, null, 0),
                event("shop", T0.plusSeconds(2), "OPENED", "tracking_missing"));
        circuits.transition(new Circuit("other", CircuitState.OPEN, T0, null, null, 0),
                event("other", T0, "OPENED", "tracking_missing"));

        List<CircuitEvent> first = circuits.events("shop", 2, null);
        assertThat(first).hasSize(2);
        assertThat(first.get(0).at()).isEqualTo(T0.plusSeconds(2));
        List<CircuitEvent> rest = circuits.events("shop", 2, first.get(1).id());
        assertThat(rest).singleElement().satisfies(e -> {
            assertThat(e.transition()).isEqualTo("OPENED");
            assertThat(e.at()).isEqualTo(T0);
        });
        assertThat(circuits.events("shop", 2, rest.get(0).id())).isEmpty();
    }

    @Test
    void retentionDeletesOldEvents() {
        circuits.transition(new Circuit("shop", CircuitState.OPEN, T0, null, null, 0),
                event("shop", T0.minusSeconds(100), "OPENED", "tracking_missing"));
        circuits.transition(new Circuit("shop", CircuitState.CLOSED, T0, null, null, 0),
                event("shop", T0, "CLOSED", "delivered_resumed"));

        assertThat(circuits.deleteEventsBefore(T0)).isEqualTo(1);
        assertThat(circuits.events("shop", 10, null)).extracting(CircuitEvent::transition).containsExactly("CLOSED");
        assertThat(circuits.deleteEventsBefore(T0)).isZero();
    }
}
