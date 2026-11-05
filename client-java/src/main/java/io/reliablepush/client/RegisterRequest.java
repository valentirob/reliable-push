package io.reliablepush.client;

import java.util.Map;
import java.util.Objects;

/** Registration of a push. {@code externalId}, {@code policy} and {@code timeoutMinutes} are optional (nullable). */
public record RegisterRequest(String userId, String externalId, FallbackPolicy policy, Integer timeoutMinutes,
                              Map<String, ?> fallback) {

    public RegisterRequest {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(fallback, "fallback");
        fallback = Map.copyOf(fallback);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder; {@code userId} and {@code fallback} are required. */
    public static final class Builder {
        private String userId;
        private String externalId;
        private FallbackPolicy policy;
        private Integer timeoutMinutes;
        private Map<String, ?> fallback;

        private Builder() {
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
            return new RegisterRequest(userId, externalId, policy, timeoutMinutes, fallback);
        }
    }
}
