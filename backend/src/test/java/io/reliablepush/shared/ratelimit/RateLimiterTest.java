package io.reliablepush.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** Manually advanced clock so refill and idle eviction are tested without sleeping. */
    private static final class FakeTime implements TimeMeter {
        final AtomicLong nanos = new AtomicLong();

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }

    @Test
    void allowsUpToLimitThenRejects() {
        RateLimiter limiter = new InMemoryRateLimiter();

        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryConsume("sender:shop", 60)).as("request %d", i + 1).isTrue();
        }

        assertThat(limiter.tryConsume("sender:shop", 60)).isFalse();
        assertThat(limiter.retryAfterSeconds("sender:shop")).isBetween(1L, 60L);
    }

    @Test
    void bucketsAreIndependentPerKey() {
        RateLimiter limiter = new InMemoryRateLimiter();

        assertThat(limiter.tryConsume("sender:a", 1)).isTrue();
        assertThat(limiter.tryConsume("sender:a", 1)).isFalse();
        assertThat(limiter.tryConsume("sender:b", 1)).isTrue();
    }

    @Test
    void refillsGraduallyAfterTheBucketRanDry() {
        FakeTime time = new FakeTime();
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(time);
        for (int i = 0; i < 60; i++) {
            limiter.tryConsume("k", 60);
        }
        assertThat(limiter.tryConsume("k", 60)).isFalse();

        time.advance(Duration.ofSeconds(1)); // greedy refill: 60 per minute = one token per second

        assertThat(limiter.tryConsume("k", 60)).isTrue();
        assertThat(limiter.tryConsume("k", 60)).isFalse();
    }

    @Test
    void retryAfterOfUnknownKeyIsOneSecond() {
        assertThat(new InMemoryRateLimiter().retryAfterSeconds("never-seen")).isEqualTo(1L);
    }

    @Test
    void idleBucketsAreEvictedByTheLazySweep() {
        FakeTime time = new FakeTime();
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(time);
        limiter.tryConsume("idle", 10);
        assertThat(limiter.size()).isEqualTo(1);

        time.advance(Duration.ofMinutes(11));
        limiter.tryConsume("active", 10); // triggers the sweep: "idle" has not been touched for 11 minutes

        assertThat(limiter.size()).isEqualTo(1);
        assertThat(limiter.tryConsume("idle", 1)).isTrue(); // recreated full
    }

    @Test
    void changedLimitReplacesTheBucket() {
        RateLimiter limiter = new InMemoryRateLimiter();
        assertThat(limiter.tryConsume("k", 1)).isTrue();
        assertThat(limiter.tryConsume("k", 1)).isFalse();

        assertThat(limiter.tryConsume("k", 5)).isTrue();
    }
}
