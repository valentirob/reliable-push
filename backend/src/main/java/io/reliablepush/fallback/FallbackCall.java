package io.reliablepush.fallback;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One webhook attempt: what the receiver gets as JSON body ({@code trackingId} doubles as the
 * {@code Idempotency-Key}). {@code reason} is the unmet policy ({@code NOT_DELIVERED} or {@code NOT_READ});
 * {@code attempt} is 1-based. {@link #toString()} leaves out the user id and the fallback content (never logged).
 */
public record FallbackCall(UUID trackingId, String userId, JsonNode fallback, String reason, int attempt) {

    @Override
    public String toString() {
        return "FallbackCall[trackingId=" + trackingId + ", reason=" + reason + ", attempt=" + attempt + "]";
    }
}
