package io.reliablepush.fallback.circuit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

/**
 * Binding of {@code reliable-push.fallback.*} (defaults as spec 2). A duration without a unit is read in minutes, as
 * for the other worker settings. {@link #toProperties()} builds the validated {@link CircuitBreakerProperties}.
 */
@ConfigurationProperties("reliable-push.fallback")
record FallbackSettings(
        @DefaultValue("false") boolean suspended,
        @DefaultValue CircuitBreaker circuitBreaker) {

    record CircuitBreaker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5m") @DurationUnit(ChronoUnit.MINUTES) Duration window,
            @DefaultValue("50") int minDue,
            @DefaultValue("0.10") double maxDeliveredRatio,
            @DefaultValue("10") int closeMinDelivered,
            @DefaultValue("1m") @DurationUnit(ChronoUnit.MINUTES) Duration minOpen,
            @DefaultValue("60m") @DurationUnit(ChronoUnit.MINUTES) Duration forceHold) {}

    CircuitBreakerProperties toProperties() {
        CircuitBreaker c = circuitBreaker;
        return new CircuitBreakerProperties(suspended, c.enabled(), c.window(), c.minDue(), c.maxDeliveredRatio(),
                c.closeMinDelivered(), c.minOpen(), c.forceHold());
    }
}
