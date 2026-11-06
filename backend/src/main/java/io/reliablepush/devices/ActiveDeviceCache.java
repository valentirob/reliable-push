package io.reliablepush.devices;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived memory of "this device id with this token version is active". Keyed by {@code did:ver} (never by the
 * token string: ECDSA signatures are malleable, so one token has many spellings). Only positive answers are kept, a
 * revoked or unknown device is always looked up again. A zero or negative TTL turns the cache off. The map is bounded:
 * when it is full, expired entries are dropped, and if that is not enough it is emptied (it is only a cache).
 */
final class ActiveDeviceCache {

    static final int MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, Long> expiresAtMillis = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlMillis;

    ActiveDeviceCache(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttlMillis = ttl.toMillis();
    }

    boolean isKnownActive(UUID deviceId, int tokenVersion) {
        if (ttlMillis <= 0) {
            return false;
        }
        String key = key(deviceId, tokenVersion);
        Long expiry = expiresAtMillis.get(key);
        if (expiry == null) {
            return false;
        }
        if (clock.millis() < expiry) {
            return true;
        }
        expiresAtMillis.remove(key, expiry);
        return false;
    }

    void markActive(UUID deviceId, int tokenVersion) {
        if (ttlMillis <= 0) {
            return;
        }
        long now = clock.millis();
        if (expiresAtMillis.size() >= MAX_ENTRIES) {
            expiresAtMillis.values().removeIf(expiry -> expiry <= now);
            if (expiresAtMillis.size() >= MAX_ENTRIES) {
                expiresAtMillis.clear();
            }
        }
        expiresAtMillis.put(key(deviceId, tokenVersion), now + ttlMillis);
    }

    /** Forgets every version of the device (after a revocation or a re-registration on this instance). */
    void evict(UUID deviceId) {
        String prefix = deviceId + ":";
        expiresAtMillis.keySet().removeIf(key -> key.startsWith(prefix));
    }

    int size() {
        return expiresAtMillis.size();
    }

    private static String key(UUID deviceId, int tokenVersion) {
        return deviceId + ":" + tokenVersion;
    }
}
