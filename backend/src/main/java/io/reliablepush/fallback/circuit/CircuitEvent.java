package io.reliablepush.fallback.circuit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the open/close history. {@code id} is assigned by the database (ignored on insert); {@code due},
 * {@code ratio} are what the detector saw, {@code suppressed} the fallbacks the finished open period suppressed,
 * {@code adminKeyId} the operator of a forced resume.
 */
public record CircuitEvent(
        long id,
        String appId,
        Instant at,
        String transition,
        String reason,
        Long due,
        BigDecimal ratio,
        Long suppressed,
        String adminKeyId) {}
