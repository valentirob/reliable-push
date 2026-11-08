package io.reliablepush.fallback.circuit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the circuit breaker configuration to every role: the worker evaluates and suppresses, the API role reports
 * {@code suspended} and {@code enabled} on the admin routes. Not profile-restricted on purpose. A wrong value fails the
 * startup with an {@link IllegalStateException} naming the key.
 */
@Configuration(proxyBeanMethods = false)
class CircuitConfig {

    @Bean
    CircuitBreakerProperties circuitBreakerProperties(FallbackSettings settings) {
        return settings.toProperties();
    }
}
