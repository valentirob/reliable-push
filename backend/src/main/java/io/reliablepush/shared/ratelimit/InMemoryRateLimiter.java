package io.reliablepush.shared.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.EstimationProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bucket4j (core) token buckets in a {@link ConcurrentHashMap}: capacity {@code perMinute}, greedy refill of
 * {@code perMinute} tokens per minute. A bucket that was not touched for ten minutes is full again anyway, so
 * dropping it loses nothing: a lazy sweep (at most once per ten minutes, triggered by normal traffic) evicts
 * those entries, which keeps the map bounded by the number of recently active keys without a scheduler.
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

    static final Duration IDLE_EVICTION = Duration.ofMinutes(10);
    private static final long MAX_RETRY_AFTER_SECONDS = 60;

    private static final class Entry {
        final Bucket bucket;
        final int perMinute;
        volatile long lastAccessNanos;

        Entry(Bucket bucket, int perMinute, long now) {
            this.bucket = bucket;
            this.perMinute = perMinute;
            this.lastAccessNanos = now;
        }
    }

    private final ConcurrentHashMap<String, Entry> buckets = new ConcurrentHashMap<>();
    private final TimeMeter time;
    private final AtomicLong lastSweepNanos;

    @Autowired
    public InMemoryRateLimiter() {
        this(TimeMeter.SYSTEM_NANOTIME);
    }

    /** Test seam: a controllable clock drives both the refill and the idle eviction. */
    InMemoryRateLimiter(TimeMeter time) {
        this.time = time;
        this.lastSweepNanos = new AtomicLong(time.currentTimeNanos());
    }

    @Override
    public boolean tryConsume(String bucketKey, int perMinute) {
        long now = time.currentTimeNanos();
        sweepIfDue(now);
        Entry entry = buckets.get(bucketKey);
        if (entry == null || entry.perMinute != perMinute) {
            entry = buckets.compute(bucketKey, (k, existing) ->
                    existing != null && existing.perMinute == perMinute ? existing : newEntry(perMinute, now));
        }
        entry.lastAccessNanos = now;
        return entry.bucket.tryConsume(1);
    }

    @Override
    public long retryAfterSeconds(String bucketKey) {
        Entry entry = buckets.get(bucketKey);
        if (entry == null) {
            return 1;
        }
        EstimationProbe probe = entry.bucket.estimateAbilityToConsume(1);
        if (probe.canBeConsumed()) {
            return 1;
        }
        long seconds = Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L);
        return Math.max(1, Math.min(MAX_RETRY_AFTER_SECONDS, seconds));
    }

    /** Number of live buckets (tests). */
    int size() {
        return buckets.size();
    }

    private Entry newEntry(int perMinute, long now) {
        Bucket bucket = Bucket.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                .withCustomTimePrecision(time)
                .build();
        return new Entry(bucket, perMinute, now);
    }

    private void sweepIfDue(long now) {
        long last = lastSweepNanos.get();
        if (now - last >= IDLE_EVICTION.toNanos() && lastSweepNanos.compareAndSet(last, now)) {
            buckets.values().removeIf(e -> now - e.lastAccessNanos >= IDLE_EVICTION.toNanos());
        }
    }
}
