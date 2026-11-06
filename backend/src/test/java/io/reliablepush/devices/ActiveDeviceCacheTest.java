package io.reliablepush.devices;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActiveDeviceCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-03T10:00:00Z"));
    private final UUID device = UUID.randomUUID();

    @Test
    void remembersAnActiveDeviceVersionUntilTheTtlPasses() {
        ActiveDeviceCache cache = new ActiveDeviceCache(clock, Duration.ofSeconds(60));
        assertThat(cache.isKnownActive(device, 0)).isFalse();

        cache.markActive(device, 0);

        assertThat(cache.isKnownActive(device, 0)).isTrue();
        assertThat(cache.isKnownActive(device, 1)).as("another version is another key").isFalse();
        assertThat(cache.isKnownActive(UUID.randomUUID(), 0)).isFalse();
        clock.advance(Duration.ofSeconds(59));
        assertThat(cache.isKnownActive(device, 0)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.isKnownActive(device, 0)).isFalse();
        assertThat(cache.size()).as("the expired entry is dropped when it is looked up").isZero();
    }

    @Test
    void evictForgetsEveryVersionOfOneDeviceOnly() {
        ActiveDeviceCache cache = new ActiveDeviceCache(clock, Duration.ofSeconds(60));
        UUID other = UUID.randomUUID();
        cache.markActive(device, 0);
        cache.markActive(device, 1);
        cache.markActive(other, 0);

        cache.evict(device);

        assertThat(cache.isKnownActive(device, 0)).isFalse();
        assertThat(cache.isKnownActive(device, 1)).isFalse();
        assertThat(cache.isKnownActive(other, 0)).isTrue();
    }

    @Test
    void zeroTtlDisablesTheCache() {
        ActiveDeviceCache cache = new ActiveDeviceCache(clock, Duration.ZERO);

        cache.markActive(device, 0);

        assertThat(cache.isKnownActive(device, 0)).isFalse();
        assertThat(cache.size()).isZero();
    }

    @Test
    void staysBounded() {
        ActiveDeviceCache cache = new ActiveDeviceCache(clock, Duration.ofSeconds(60));
        for (int i = 0; i < ActiveDeviceCache.MAX_ENTRIES + 5; i++) {
            cache.markActive(UUID.randomUUID(), 0);
        }

        assertThat(cache.size()).isLessThanOrEqualTo(ActiveDeviceCache.MAX_ENTRIES);
    }
}
