package io.reliablepush.harness.devices;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Devices that registered with the backend on their own (the sample app on an emulator), reported to the harness so the
 * console can list them next to the simulated ones. In memory only; the harness holds no token for them. At most
 * {@value #MAX_DEVICES}: the oldest is evicted. A device id registered again replaces its earlier entry.
 */
@Component
public class RealDeviceRegistry {

    static final int MAX_DEVICES = 50;

    /** A device the harness only knows about; never carries a device token. */
    public record RealDevice(String appId, String userId, UUID deviceId, Platform platform, String label,
                             Instant registeredAt) {}

    private final Clock clock;
    private final ArrayDeque<RealDevice> devices = new ArrayDeque<>();

    @Autowired
    public RealDeviceRegistry() {
        this(Clock.systemUTC());
    }

    RealDeviceRegistry(Clock clock) {
        this.clock = clock;
    }

    public RealDevice add(String appId, String userId, UUID deviceId, Platform platform, String label) {
        RealDevice d = new RealDevice(appId, userId, deviceId, platform, label, clock.instant());
        synchronized (devices) {
            devices.removeIf(e -> e.deviceId().equals(deviceId));
            devices.addLast(d);
            while (devices.size() > MAX_DEVICES) {
                devices.removeFirst();
            }
        }
        return d;
    }

    /** Oldest first, optionally for one app. */
    public List<RealDevice> all(String appId) {
        synchronized (devices) {
            return devices.stream().filter(d -> appId == null || d.appId().equals(appId)).toList();
        }
    }
}
