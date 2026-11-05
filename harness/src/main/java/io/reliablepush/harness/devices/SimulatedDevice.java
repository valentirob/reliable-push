package io.reliablepush.harness.devices;

import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a simulated device. The device token is deliberately not a component: it lives only inside
 * {@link DeviceSimulator}.
 */
public record SimulatedDevice(
        UUID deviceId,
        String appId,
        String userId,
        Platform platform,
        AndroidMode androidMode,
        Instant tokenExpiresAt,
        boolean online) {}
