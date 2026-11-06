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

    /**
     * A device the harness only knows about; never carries a backend device token. {@code fcmToken} (nullable) is the
     * device's FCM registration token, used only to send real pushes: it is never returned by an endpoint and is
     * redacted from {@link #toString()}. {@code apnsToken} (nullable) is the iOS device's hex APNs token, treated the
     * same way.
     */
    public record RealDevice(String appId, String userId, UUID deviceId, Platform platform, String label,
                             Instant registeredAt, String fcmToken, String apnsToken) {
        @Override
        public String toString() {
            return "RealDevice[appId=" + appId + ", deviceId=" + deviceId + ", platform=" + platform
                    + ", fcmToken=" + (fcmToken == null ? "none" : "<redacted>")
                    + ", apnsToken=" + (apnsToken == null ? "none" : "<redacted>") + "]";
        }
    }

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
        return add(appId, userId, deviceId, platform, label, null);
    }

    public RealDevice add(String appId, String userId, UUID deviceId, Platform platform, String label, String fcmToken) {
        RealDevice d = new RealDevice(appId, userId, deviceId, platform, label, clock.instant(), fcmToken, null);
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

    /** Replaces (or, with null, drops) the FCM token of a device; false when the device is unknown. */
    public boolean setFcmToken(UUID deviceId, String fcmToken) {
        synchronized (devices) {
            List<RealDevice> all = List.copyOf(devices);
            boolean found = false;
            devices.clear();
            for (RealDevice d : all) {
                found |= d.deviceId().equals(deviceId);
                devices.addLast(d.deviceId().equals(deviceId) ? new RealDevice(d.appId(), d.userId(), d.deviceId(),
                        d.platform(), d.label(), d.registeredAt(), fcmToken, d.apnsToken()) : d);
            }
            return found;
        }
    }

    /** Drops the FCM token of a device only if it still equals {@code expected}; true when it was dropped. */
    public boolean clearFcmToken(UUID deviceId, String expected) {
        synchronized (devices) {
            List<RealDevice> all = List.copyOf(devices);
            boolean cleared = false;
            devices.clear();
            for (RealDevice d : all) {
                boolean match = d.deviceId().equals(deviceId) && expected != null && expected.equals(d.fcmToken());
                cleared |= match;
                devices.addLast(match ? new RealDevice(d.appId(), d.userId(), d.deviceId(), d.platform(), d.label(),
                        d.registeredAt(), null, d.apnsToken()) : d);
            }
            return cleared;
        }
    }

    /** Replaces the APNs token of a device; false when the device is unknown. */
    public boolean setApnsToken(UUID deviceId, String apnsToken) {
        synchronized (devices) {
            List<RealDevice> all = List.copyOf(devices);
            boolean found = false;
            devices.clear();
            for (RealDevice d : all) {
                found |= d.deviceId().equals(deviceId);
                devices.addLast(d.deviceId().equals(deviceId) ? new RealDevice(d.appId(), d.userId(), d.deviceId(),
                        d.platform(), d.label(), d.registeredAt(), d.fcmToken(), apnsToken) : d);
            }
            return found;
        }
    }

    /** Drops the APNs token of a device only if it still equals {@code expected}; true when it was dropped. */
    public boolean clearApnsToken(UUID deviceId, String expected) {
        synchronized (devices) {
            List<RealDevice> all = List.copyOf(devices);
            boolean cleared = false;
            devices.clear();
            for (RealDevice d : all) {
                boolean match = d.deviceId().equals(deviceId) && expected != null && expected.equals(d.apnsToken());
                cleared |= match;
                devices.addLast(match ? new RealDevice(d.appId(), d.userId(), d.deviceId(), d.platform(), d.label(),
                        d.registeredAt(), d.fcmToken(), null) : d);
            }
            return cleared;
        }
    }

    /** The devices of one user of an app, oldest first. */
    public List<RealDevice> forUser(String appId, String userId) {
        synchronized (devices) {
            return devices.stream().filter(d -> d.appId().equals(appId) && d.userId().equals(userId)).toList();
        }
    }
}
