package io.reliablepush.apps;

import io.reliablepush.apps.ReliablePushProperties.IdentityType;
import io.reliablepush.shared.config.Secret;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.lang.Nullable;

/** Fully resolved, validated configuration of one customer app. Secrets are {@link Secret}s (redacted in logs). */
public record AppConfig(
        String appId,
        /** Lowercase hex SHA-256 of the sender API key (the raw key is never stored). */
        String senderApiKeySha256,
        Identity identity,
        Duration deviceTokenValidity,
        /**
         * How long after its expiry a device token still counts (sidecar revision spec 2.3, 2.4): a device whose newest
         * token expired less than this ago is active. Zero means no grace; never negative.
         */
        Duration deviceTokenEventsGrace,
        String defaultPolicy,
        int defaultTimeoutMinutes,
        RateLimits rateLimits,
        Fallback fallback) {

    public record Identity(
            IdentityType type,
            String issuer,
            String audience,
            String userClaim,
            @Nullable String jwksUri,
            /** Present only for {@code signed-grant}. */
            @Nullable Secret grantSecret) {}

    public record RateLimits(int devicePerMinute, int senderPerMinute) {}

    public record Fallback(
            URI url,
            Secret hmacSecret,
            int maxAttempts,
            List<Duration> backoff,
            int triggerWindowMinutes,
            Duration httpTimeout) {

        /**
         * Delay between attempt {@code attempt} and the next one (1-based). When the backoff list is shorter than
         * {@code maxAttempts - 1}, its last delay repeats.
         */
        public Duration backoffAfterAttempt(int attempt) {
            return backoff.get(Math.min(attempt, backoff.size()) - 1);
        }

        /** Sum of all delays between the {@code maxAttempts} attempts. */
        public Duration cumulativeBackoff() {
            Duration total = Duration.ZERO;
            for (int attempt = 1; attempt < maxAttempts; attempt++) {
                total = total.plus(backoffAfterAttempt(attempt));
            }
            return total;
        }
    }
}
