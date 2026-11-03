package io.reliablepush.shared.ratelimit;

/**
 * Token-bucket rate limiting by an opaque bucket key ({@code sender:<appId>}, {@code device:<deviceId>}).
 * The in-memory implementation is per instance; the interface allows a shared store later (spec section 5).
 */
public interface RateLimiter {

    /** Takes one token from the bucket of {@code bucketKey} (capacity and refill: {@code perMinute}). */
    boolean tryConsume(String bucketKey, int perMinute);

    /** Whole seconds, in {@code 1..60}, until a rejected caller can expect a token (for {@code Retry-After}). */
    long retryAfterSeconds(String bucketKey);
}
