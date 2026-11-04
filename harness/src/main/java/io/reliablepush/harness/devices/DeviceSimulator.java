package io.reliablepush.harness.devices;

import io.reliablepush.harness.config.HarnessProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
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
public class DeviceSimulator implements PushReceiver, AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(DeviceSimulator.class);
    static final DeviceBehaviour DEFAULT_BEHAVIOUR = new DeviceBehaviour.DeliverAfter(Duration.ofSeconds(1));
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    /**
     * What a device reported. {@code outcome}: backend per-item result, or RATE_LIMITED / UNAUTHORIZED / UNAVAILABLE.
     * {@code retryAfterSeconds}: the backend's {@code Retry-After} when it answered 429/503 with one, else null.
     */
    public record DeviceEventRecord(UUID trackingId, UUID deviceId, String type, Instant sentAt, String outcome,
                                    Integer retryAfterSeconds) {

        public DeviceEventRecord(UUID trackingId, UUID deviceId, String type, Instant sentAt, String outcome) {
            this(trackingId, deviceId, type, sentAt, outcome, null);
        }
    }

    /** Bound of the token history kept for the log-hygiene scan. */
    static final int TOKEN_HISTORY = 10_000;

    private record Pending(UUID trackingId, String type, Instant occurredAt) {}

    /**
     * Mutable device state. Token fields and {@code online}/{@code removed} are volatile so {@link #view()} never
     * blocks; token changes are serialised by {@code authLock} (held across network calls, never by readers);
     * {@code queue} and {@code draining} are guarded by {@code gate} (never held across I/O).
     */
    private static final class DeviceState {
        final UUID id;
        final String appId;
        final String userId;
        final Platform platform;
        final AndroidMode androidMode;
        final Object authLock = new Object();
        final Object gate = new Object();
        volatile String token;
        volatile Instant issuedAt;
        volatile Instant expiresAt;
        volatile boolean online = true;
        volatile boolean removed;
        final ArrayDeque<Pending> queue = new ArrayDeque<>();
        boolean draining;

        DeviceState(UUID id, String appId, String userId, Platform platform, AndroidMode androidMode) {
            this.id = id;
            this.appId = appId;
            this.userId = userId;
            this.platform = platform;
            this.androidMode = androidMode;
        }

        void setToken(String token, Instant issuedAt, Instant expiresAt) {
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.token = token;
        }

        SimulatedDevice view() {
            return new SimulatedDevice(id, appId, userId, platform, androidMode, expiresAt, online);
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
    /** Every token issued to any device (last {@value #TOKEN_HISTORY}); only for {@link #issuedTokensForLogHygieneScan}. */
    private final ArrayDeque<String> issuedTokens = new ArrayDeque<>();
    private final List<Consumer<DeviceEventRecord>> listeners = new CopyOnWriteArrayList<>();
    /** Only fires timers; all blocking HTTP runs on {@link #io}. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().daemon().name("device-sim-timer").unstarted(r));
    private final ExecutorService io =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("device-sim-io-", 0).factory());

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
        remember(r.token());
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

    /** Every simulated device (optionally of one app), ordered by app, user and id. No token is exposed. */
    public List<SimulatedDevice> allDevices(String appId) {
        return devices.values().stream()
                .filter(d -> appId == null || d.appId.equals(appId))
                .map(DeviceState::view)
                .sorted(Comparator.comparing(SimulatedDevice::appId).thenComparing(SimulatedDevice::userId)
                        .thenComparing(SimulatedDevice::deviceId))
                .toList();
    }

    public SimulatedDevice device(UUID deviceId) {
        return state(deviceId).view();
    }

    /** Revokes the device on the backend ({@code DELETE /v1/devices/me}) and forgets it. */
    public void unregister(UUID deviceId) {
        DeviceState s = state(deviceId);
        // forget first: pending timers, queued events and in-flight posts see `removed` and drop their work
        s.removed = true;
        devices.remove(deviceId);
        synchronized (s.gate) {
            s.queue.clear();
        }
        var r = api.revoke(s.token);
        if (!r.ok() && r.status() != 401) {
            throw new IllegalStateException("device revocation failed: HTTP status " + r.status());
        }
    }

    /**
     * INTERNAL — for the log-hygiene scan only ({@code scenarios} package): every device token issued during this
     * harness's lifetime (bounded), including replaced ones. Never return it through any endpoint and never log it.
     */
    public List<String> issuedTokensForLogHygieneScan() {
        synchronized (issuedTokens) {
            return List.copyOf(issuedTokens);
        }
    }

    public void onEvent(Consumer<DeviceEventRecord> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * A push for the user's devices arrived: each device follows its behaviour from {@code perDevice} (default
     * {@code DeliverAfter(1s)}).
     */
    @Override
    public void receivePush(UUID trackingId, String appId, String userId, Map<UUID, DeviceBehaviour> perDevice) {
        Map<UUID, DeviceBehaviour> behaviours = perDevice == null ? Map.of() : perDevice;
        for (DeviceState s : devices.values()) {
            if (!s.appId.equals(appId) || !s.userId.equals(userId)) {
                continue;
            }
            switch (behaviours.getOrDefault(s.id, DEFAULT_BEHAVIOUR)) {
                case DeviceBehaviour.DeliverAfter b -> schedule(s, b.d(), () -> emit(s, trackingId, "DELIVERED"));
                case DeviceBehaviour.ReadAfter b -> schedule(s, b.deliver(), () -> {
                    emit(s, trackingId, "DELIVERED");
                    Duration gap = b.read().minus(b.deliver());
                    // READ is armed only after DELIVERED was appended, so the two can never swap
                    if (gap.isZero()) {
                        emit(s, trackingId, "READ");
                    } else {
                        schedule(s, gap, () -> emit(s, trackingId, "READ"));
                    }
                });
                case DeviceBehaviour.OfflineThenLate b -> schedule(s, b.late(), () -> emit(s, trackingId, "DELIVERED"));
                case DeviceBehaviour.Never b -> { }
            }
        }
    }

    /** Refreshes the token when less than 20 % of its validity is left (spec 7.1). Offline devices do nothing. */
    public void refreshIfNearExpiry(UUID deviceId) {
        refreshIfNearExpiry(state(deviceId));
    }

    /**
     * Refreshes the token now, whatever its remaining validity (the console's "Refresh token"); a 401 re-registers
     * the device. An offline device is not touched.
     *
     * @return the HTTP status of the refresh call (200 = new token), 0 when the device is offline or on timeout
     */
    public int forceRefresh(UUID deviceId) {
        DeviceState s = state(deviceId);
        if (!s.online || s.removed) {
            return 0;
        }
        synchronized (s.authLock) {
            int status = refreshNow(s);
            if (status == 401 && reRegister(s)) {
                return 200;
            }
            return status;
        }
    }

    public void setOnline(UUID deviceId, boolean online) {
        DeviceState s = state(deviceId);
        s.online = online;
        if (online) {
            kick(s);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
        io.shutdownNow();
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

    private void schedule(DeviceState s, Duration delay, Runnable task) {
        scheduler.schedule(() -> {
            if (s.removed) {
                return;
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.warn("device {} timer task failed: {}", s.id, e.getClass().getSimpleName());
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Appends to the device's FIFO queue and makes sure exactly one drain loop is running. */
    private void emit(DeviceState s, UUID trackingId, String type) {
        synchronized (s.gate) {
            if (s.removed) {
                return;
            }
            s.queue.add(new Pending(trackingId, type, clock.instant()));
        }
        kick(s);
    }

    private void kick(DeviceState s) {
        synchronized (s.gate) {
            if (s.draining || s.removed || !s.online || s.queue.isEmpty()) {
                return;
            }
            s.draining = true;
        }
        try {
            io.execute(() -> drain(s));
        } catch (RejectedExecutionException e) {
            synchronized (s.gate) {
                s.draining = false; // simulator closed
            }
        }
    }

    /** The only poster of a device's events: FIFO, stops (leaving the rest queued) as soon as the device is offline. */
    private void drain(DeviceState s) {
        while (true) {
            Pending p;
            synchronized (s.gate) {
                if (s.removed || !s.online || s.queue.isEmpty()) {
                    s.draining = false;
                    return;
                }
                p = s.queue.poll();
            }
            try {
                post(s, p);
            } catch (RuntimeException e) {
                LOG.warn("device {} could not report {}: {}", s.id, p.type(), e.getClass().getSimpleName());
            }
        }
    }

    private void post(DeviceState s, Pending p) {
        Instant sentAt = clock.instant();
        refreshIfNearExpiry(s);
        if (s.removed) {
            return;
        }
        String outcome;
        Integer retryAfter = null;
        try {
            var result = withAuth(s, token -> api.postEvents(token, List.of(
                    new DeviceApiClient.EventItem(p.trackingId(), p.type(), p.occurredAt()))));
            outcome = switch (result.status()) {
                case 200 -> result.value().outcomes().isEmpty() ? "UNKNOWN" : result.value().outcomes().get(0);
                case 429 -> {
                    retryAfter = result.retryAfterSeconds();
                    yield "RATE_LIMITED";
                }
                case 401 -> "UNAUTHORIZED";
                case 503 -> {
                    retryAfter = result.retryAfterSeconds();
                    yield "UNAVAILABLE";
                }
                case 0 -> "UNAVAILABLE";
                default -> "ERROR_" + result.status();
            };
        } catch (RuntimeException e) {
            outcome = "UNAVAILABLE";
        }
        DeviceEventRecord record = new DeviceEventRecord(p.trackingId(), s.id, p.type(), sentAt, outcome, retryAfter);
        for (Consumer<DeviceEventRecord> l : listeners) {
            try {
                l.accept(record);
            } catch (RuntimeException e) {
                LOG.warn("device event listener failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Runs the call; on 401 tries one refresh and, if that succeeds but the retry is 401 again or the refresh itself
     * was 401, one re-registration; then gives up with the last result.
     */
    private <T> DeviceApiClient.Result<T> withAuth(DeviceState s,
            Function<String, DeviceApiClient.Result<T>> call) {
        var result = call.apply(s.token);
        if (result.status() != 401) {
            return result;
        }
        int refreshStatus = refreshNow(s);
        boolean reRegister = refreshStatus == 401;
        if (refreshStatus == 200) {
            result = call.apply(s.token);
            if (result.status() != 401) {
                return result;
            }
            reRegister = true;
        }
        if (reRegister && reRegister(s)) {
            return call.apply(s.token);
        }
        return result;
    }

    private void remember(String token) {
        synchronized (issuedTokens) {
            issuedTokens.addLast(token);
            while (issuedTokens.size() > TOKEN_HISTORY) {
                issuedTokens.removeFirst();
            }
        }
    }

    private void refreshIfNearExpiry(DeviceState s) {
        if (!s.online || s.removed) {
            return;
        }
        synchronized (s.authLock) {
            long validity = Duration.between(s.issuedAt, s.expiresAt).toMillis();
            long remaining = Duration.between(clock.instant(), s.expiresAt).toMillis();
            if (remaining * 5 < validity && refreshNow(s) == 401) {
                reRegister(s);
            }
        }
    }

    /** @return the HTTP status of the refresh call (200 = new token stored), 0 on timeout / I/O failure */
    private int refreshNow(DeviceState s) {
        synchronized (s.authLock) {
            if (s.removed) {
                return 0;
            }
            var r = api.refresh(s.token);
            if (r.ok()) {
                s.setToken(r.value().token(), clock.instant(), r.value().expiresAt());
                remember(r.value().token());
            }
            return r.status();
        }
    }

    private boolean reRegister(DeviceState s) {
        synchronized (s.authLock) {
            if (s.removed) {
                return false;
            }
            var r = api.register(s.appId, minter.mint(s.appId, s.userId), s.platform, s.id);
            if (!r.ok()) {
                return false;
            }
            if (s.removed) { // unregistered while the call was in flight: do not leave a live token behind
                api.revoke(r.value().token());
                return false;
            }
            s.setToken(r.value().token(), clock.instant(), r.value().expiresAt());
            remember(r.value().token());
            return true;
        }
    }
}
