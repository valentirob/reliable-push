package io.reliablepush.fallback;

import java.time.Instant;
import java.util.UUID;

/**
 * One webhook call the fallback worker made (ops dashboard spec 3.1). {@code outcome} is {@code SUCCESS},
 * {@code RETRYABLE} or {@code FINAL}; {@code httpStatus} is set when the endpoint answered; {@code reason} is the
 * short log code ({@code http_503}, {@code timeout}, ...), never a response body.
 */
public record FallbackAttempt(
        UUID notificationId,
        int attempt,
        Instant startedAt,
        long durationMs,
        String outcome,
        Integer httpStatus,
        String reason) {}
