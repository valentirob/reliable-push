package io.reliablepush.fallback.circuit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** {@code reliable-push.fallback.*} (spec 2): defaults, minutes for bare numbers, startup validation. */
class CircuitBreakerPropertiesTest {

    private static final String P = "reliable-push.fallback.";

    private static CircuitBreakerProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("reliable-push.fallback", FallbackSettings.class)
                .toProperties();
    }

    @Test
    void defaultsMatchSpec() {
        CircuitBreakerProperties d = CircuitBreakerProperties.defaults();
        assertThat(d.suspended()).isFalse();
        assertThat(d.enabled()).isTrue();
        assertThat(d.window()).isEqualTo(Duration.ofMinutes(5));
        assertThat(d.minDue()).isEqualTo(50);
        assertThat(d.maxDeliveredRatio()).isEqualTo(0.10);
        assertThat(d.closeMinDelivered()).isEqualTo(10);
        assertThat(d.minOpen()).isEqualTo(Duration.ofMinutes(1));
        assertThat(d.forceHold()).isEqualTo(Duration.ofMinutes(60));
        assertThat(bind(Map.of(P + "suspended", "false"))).isEqualTo(d);
    }

    @Test
    void bindsEveryKey() {
        CircuitBreakerProperties p = bind(Map.of(
                P + "suspended", "true",
                P + "circuit-breaker.enabled", "false",
                P + "circuit-breaker.window", "30s",
                P + "circuit-breaker.min-due", "7",
                P + "circuit-breaker.max-delivered-ratio", "0.5",
                P + "circuit-breaker.close-min-delivered", "3",
                P + "circuit-breaker.min-open", "2m",
                P + "circuit-breaker.force-hold", "1h"));
        assertThat(p).isEqualTo(new CircuitBreakerProperties(
                true, false, Duration.ofSeconds(30), 7, 0.5, 3, Duration.ofMinutes(2), Duration.ofHours(1)));
    }

    @Test
    void bareNumbersAreMinutes() {
        CircuitBreakerProperties p = bind(Map.of(
                P + "circuit-breaker.window", "5",
                P + "circuit-breaker.min-open", "2",
                P + "circuit-breaker.force-hold", "90"));
        assertThat(p.window()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.minOpen()).isEqualTo(Duration.ofMinutes(2));
        assertThat(p.forceHold()).isEqualTo(Duration.ofMinutes(90));
    }

    @ParameterizedTest
    @CsvSource({
        "window, 0",
        "min-open, -1",
        "force-hold, 0",
        "min-due, 0",
        "close-min-delivered, 0",
        "max-delivered-ratio, 0",
        "max-delivered-ratio, 1.01"
    })
    void rejectsInvalid(String key, String value) {
        assertThatThrownBy(() -> bind(Map.of(P + "circuit-breaker." + key, value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reliable-push.fallback.circuit-breaker." + key);
    }

    @Test
    void ratioOneIsAccepted() {
        assertThatCode(() -> bind(Map.of(P + "circuit-breaker.max-delivered-ratio", "1.0"))).doesNotThrowAnyException();
    }
}
