package io.reliablepush.tracking;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One event reported by a device. {@code occurredAt} is the device clock: it is stored (clamped by the server) but
 * never used for state decisions. Unknown JSON fields are ignored.
 */
public record DeviceEvent(@NotNull UUID trackingId, @NotNull EventType type, @NotNull Instant occurredAt) {}
