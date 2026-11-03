package io.reliablepush.notifications;

import java.time.Instant;
import java.util.UUID;

/**
 * A row the fallback worker has just claimed ({@link NotificationRepository#claimDue}): now {@code DISPATCHING} with a
 * lease. {@code previousState} is the state before the claim: {@code DISPATCHING} means a retry whose backoff is over
 * or a re-claim after a lease ran out (the row does not tell these apart). {@code dueAt} is the original due time (never moved). {@code fallbackPayload}
 * is the opaque JSON text for the webhook; it and {@code userId} are left out of {@link #toString()}.
 */
public record ClaimedNotification(
        UUID id,
        String appId,
        String userId,
        FallbackPolicy policy,
        String fallbackPayload,
        Instant dueAt,
        int fallbackAttempts,
        NotificationState previousState) {

    @Override
    public String toString() {
        return "ClaimedNotification[id=" + id + ", appId=" + appId + ", policy=" + policy + ", dueAt=" + dueAt
                + ", fallbackAttempts=" + fallbackAttempts
                + ", previousState=" + previousState + "]";
    }
}
