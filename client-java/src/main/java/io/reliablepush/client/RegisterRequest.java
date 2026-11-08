package io.reliablepush.client;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Registration of a push. {@code trackingId} (a UUID the sender generates, for example with
 * {@code TrackingIds.newId()} of reliable-push-payload) and {@code userId} are required; {@code externalId},
 * {@code policy} and {@code timeoutMinutes} are optional (nullable).
 */
public record RegisterRequest(String trackingId, String userId, String externalId, FallbackPolicy policy, Integer timeoutMinutes,
                              Map<String, ?> fallback) {

    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public RegisterRequest {
        Objects.requireNonNull(trackingId, "trackingId");
        if (!UUID_FORMAT.matcher(trackingId).matches()) {
            throw new IllegalArgumentException("trackingId must be a UUID (8-4-4-4-12 hex digits)");
        }
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(fallback, "fallback");
        fallback = Map.copyOf(fallback);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder; {@code trackingId}, {@code userId} and {@code fallback} are required. */
    public static final class Builder {
        private String trackingId;
        private String userId;
        private String externalId;
        private FallbackPolicy policy;
        private Integer timeoutMinutes;
        private Map<String, ?> fallback;

        private Builder() {
        }

        public Builder trackingId(String trackingId) {
            this.trackingId = trackingId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder externalId(String externalId) {
            this.externalId = externalId;
            return this;
        }

        public Builder policy(FallbackPolicy policy) {
            this.policy = policy;
            return this;
        }

        public Builder timeoutMinutes(Integer timeoutMinutes) {
            this.timeoutMinutes = timeoutMinutes;
            return this;
        }

        public Builder fallback(Map<String, ?> fallback) {
            this.fallback = fallback;
            return this;
        }

        public RegisterRequest build() {
            return new RegisterRequest(trackingId, userId, externalId, policy, timeoutMinutes, fallback);
        }
    }
}
