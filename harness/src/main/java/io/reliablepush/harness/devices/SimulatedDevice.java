package io.reliablepush.harness.devices;

import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a simulated device. The device token is deliberately not a component: it lives only inside
 * {@link DeviceSimulator}. {@code renewalNeeded}: an events call was answered 401 (the SDKs' stored flag; the next
 * {@link DeviceSimulator#ensureRegistered} renews the token). {@code queuedEvents}: events kept on the device, not yet
 * acknowledged by the backend (offline, or kept after a 401).
 */
public record SimulatedDevice(
        UUID deviceId,
        String appId,
        String userId,
        Platform platform,
        AndroidMode androidMode,
        Instant tokenExpiresAt,
        boolean online,
        boolean renewalNeeded,
        int queuedEvents) {}
