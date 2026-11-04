package io.reliablepush.harness.devices;

import io.reliablepush.harness.config.HarnessProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Simulated iOS/Android devices that talk to the real backend device API. Each device keeps its device JWT private
 * (inside this class only; no method returns it and nothing logs it), reacts to simulated pushes according to its
 * {@link DeviceBehaviour} and reports DELIVERED/READ events. Behaviour delays are wall-clock durations.
 */
@Component
public class DeviceSimulator implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(DeviceSimulator.class);
    static final DeviceBehaviour DEFAULT_BEHAVIOUR = new DeviceBehaviour.DeliverAfter(Duration.ofSeconds(1));
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    /** What a device reported. {@code outcome}: backend per-item result, or RATE_LIMITED / UNAUTHORIZED / UNAVAILABLE. */
    public record DeviceEventRecord(UUID trackingId, UUID deviceId, String type, Instant sentAt, String outcome) {}

    private record Pending(UUID trackingId, String type, Instant occurredAt) {}

    /** Mutable device state; the token fields are guarded by {@code this}, the queue by {@code gate}. */
    private static final class DeviceState {
        final UUID id;
        final String appId;
        final String userId;
        final Platform platform;
        final AndroidMode androidMode;
        private String token;
        private Instant issuedAt;
        private Instant expiresAt;
        final Object gate = new Object();
        private boolean online = true;
        private final ArrayDeque<Pending> queue = new ArrayDeque<>();

        DeviceState(UUID id, String appId, String userId, Platform platform, AndroidMode androidMode) {
            this.id = id;
            this.appId = appId;
            this.userId = userId;
            this.platform = platform;
            this.androidMode = androidMode;
        }

        synchronized String token() {
            return token;
        }

        synchronized void setToken(String token, Instant issuedAt, Instant expiresAt) {
            this.token = token;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
        }

        SimulatedDevice view() {
            synchronized (this) {
                synchronized (gate) {
                    return new SimulatedDevice(id, appId, userId, platform, androidMode, expiresAt, online);
                }
            }
        }

        @Override
        public String toString() {
            return "DeviceState[" + id + "]";
        }
    }

    private final GrantMinter minter;
    private final Clock clock;
    private final DeviceApiClient api;
    private final Map<UUID, DeviceState> devices = new ConcurrentHashMap<>();
    private final List<Consumer<DeviceEventRecord>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(4, Thread.ofVirtual().name("device-sim-", 0).factory());

    @Autowired
    public DeviceSimulator(HarnessProperties properties, GrantMinter minter) {
        this(properties, minter, Clock.systemUTC(), HTTP_TIMEOUT);
    }

    DeviceSimulator(HarnessProperties properties, GrantMinter minter, Clock clock, Duration httpTimeout) {
        this.minter = minter;
        this.clock = clock;
        this.api = new DeviceApiClient(properties.backendUrl(), httpTimeout);
    }

    /** Registers a new device for the user with a freshly minted signed grant. */
    public SimulatedDevice register(String appId, String userId, Platform platform, AndroidMode mode) {
        Objects.requireNonNull(platform, "platform");
        if (platform == Platform.IOS && mode != null) {
            throw new IllegalArgumentException("androidMode is only valid for ANDROID devices");
        }
        var result = api.register(appId, minter.mint(appId, userId), platform, null);
        if (!result.ok()) {
            throw new IllegalStateException("device registration failed: HTTP status " + result.status());
        }
        DeviceApiClient.Registered r = result.value();
        DeviceState state = new DeviceState(r.deviceId(), appId, userId, platform, mode);
        state.setToken(r.token(), clock.instant(), r.expiresAt());
        devices.put(state.id, state);
        return state.view();
    }

    public List<SimulatedDevice> devices(String appId, String userId) {
        return devices.values().stream()
                .filter(d -> d.appId.equals(appId) && d.userId.equals(userId))
                .map(DeviceState::view)
                .sorted(Comparator.comparing(SimulatedDevice::deviceId))
                .toList();
    }

    public SimulatedDevice device(UUID deviceId) {
        return state(deviceId).view();
    }

    /** Revokes the device on the backend ({@code DELETE /v1/devices/me}) and forgets it. */
    public void unregister(UUID deviceId) {
        DeviceState s = state(deviceId);
        var r = api.revoke(s.token());
        devices.remove(deviceId);
        if (!r.ok() && r.status() != 401) {
            throw new IllegalStateException("device revocation failed: HTTP status " + r.status());
        }
    }

    public void onEvent(Consumer<DeviceEventRecord> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * A push for the user's devices arrived: each device follows its behaviour from {@code perDevice} (default
     * {@code DeliverAfter(1s)}).
     */
    public void receivePush(UUID trackingId, String appId, String userId, Map<UUID, DeviceBehaviour> perDevice) {
        Map<UUID, DeviceBehaviour> behaviours = perDevice == null ? Map.of() : perDevice;
        for (DeviceState s : devices.values()) {
            if (!s.appId.equals(appId) || !s.userId.equals(userId)) {
                continue;
            }
            switch (behaviours.getOrDefault(s.id, DEFAULT_BEHAVIOUR)) {
                case DeviceBehaviour.DeliverAfter b -> schedule(s, trackingId, "DELIVERED", b.d());
                case DeviceBehaviour.ReadAfter b -> {
                    schedule(s, trackingId, "DELIVERED", b.deliver());
                    schedule(s, trackingId, "READ", b.read());
                }
                case DeviceBehaviour.OfflineThenLate b -> schedule(s, trackingId, "DELIVERED", b.late());
                case DeviceBehaviour.Never b -> { }
            }
        }
    }

    /** Refreshes the token when less than 20 % of its validity is left (spec 7.1). Offline devices do nothing. */
    public void refreshIfNearExpiry(UUID deviceId) {
        refreshIfNearExpiry(state(deviceId));
    }

    public void setOnline(UUID deviceId, boolean online) {
        DeviceState s = state(deviceId);
        List<Pending> toFlush;
        synchronized (s.gate) {
            s.online = online;
            if (!online || s.queue.isEmpty()) {
                return;
            }
            toFlush = new ArrayList<>(s.queue);
            s.queue.clear();
        }
        executor.execute(() -> toFlush.forEach(p -> post(s, p)));
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    @Override
    public String toString() {
        return "DeviceSimulator[devices=" + devices.size() + "]";
    }

    // ---------------------------------------------------------------------------------------------------------

    private DeviceState state(UUID deviceId) {
        DeviceState s = devices.get(deviceId);
        if (s == null) {
            throw new IllegalArgumentException("unknown device: " + deviceId);
        }
        return s;
    }

    private void schedule(DeviceState s, UUID trackingId, String type, Duration delay) {
        executor.schedule(() -> {
            try {
                emit(s, new Pending(trackingId, type, clock.instant()));
            } catch (RuntimeException e) {
                LOG.warn("device {} could not report {}: {}", s.id, type, e.getClass().getSimpleName());
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void emit(DeviceState s, Pending p) {
        synchronized (s.gate) {
            if (!s.online) {
                s.queue.add(p);
                return;
            }
        }
        post(s, p);
    }

    private void post(DeviceState s, Pending p) {
        String outcome;
        Instant sentAt = clock.instant();
        try {
            refreshIfNearExpiry(s);
            var result = withAuth(s, token -> api.postEvents(token, List.of(
                    new DeviceApiClient.EventItem(p.trackingId(), p.type(), p.occurredAt()))));
            outcome = switch (result.status()) {
                case 200 -> result.value().outcomes().isEmpty() ? "UNKNOWN" : result.value().outcomes().get(0);
                case 429 -> "RATE_LIMITED";
                case 401 -> "UNAUTHORIZED";
                case 503, 0 -> "UNAVAILABLE";
                default -> "ERROR_" + result.status();
            };
        } catch (RuntimeException e) {
            outcome = "UNAVAILABLE";
        }
        DeviceEventRecord record = new DeviceEventRecord(p.trackingId(), s.id, p.type(), sentAt, outcome);
        for (Consumer<DeviceEventRecord> l : listeners) {
            try {
                l.accept(record);
            } catch (RuntimeException e) {
                LOG.warn("device event listener failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** Runs the call; on 401 tries one refresh, then one re-registration, then gives up with the last result. */
    private <T> DeviceApiClient.Result<T> withAuth(DeviceState s,
            Function<String, DeviceApiClient.Result<T>> call) {
        var result = call.apply(s.token());
        if (result.status() != 401) {
            return result;
        }
        if (refreshNow(s)) {
            result = call.apply(s.token());
            if (result.status() != 401) {
                return result;
            }
        }
        if (reRegister(s)) {
            return call.apply(s.token());
        }
        return result;
    }

    private void refreshIfNearExpiry(DeviceState s) {
        synchronized (s) {
            if (!s.online) {
                return;
            }
            long validity = Duration.between(s.issuedAt, s.expiresAt).toMillis();
            long remaining = Duration.between(clock.instant(), s.expiresAt).toMillis();
            if (remaining * 5 < validity) {
                if (!refreshNow(s)) {
                    reRegister(s);
                }
            }
        }
    }

    private boolean refreshNow(DeviceState s) {
        synchronized (s) {
            var r = api.refresh(s.token);
            if (!r.ok()) {
                return false;
            }
            s.setToken(r.value().token(), clock.instant(), r.value().expiresAt());
            return true;
        }
    }

    private boolean reRegister(DeviceState s) {
        synchronized (s) {
            var r = api.register(s.appId, minter.mint(s.appId, s.userId), s.platform, s.id);
            if (!r.ok()) {
                return false;
            }
            s.setToken(r.value().token(), clock.instant(), r.value().expiresAt());
            return true;
        }
    }
}
