package io.reliablepush.notifications;

import java.time.Instant;
import java.util.UUID;

/**
 * A row the fallback worker has just claimed ({@link NotificationRepository#claimDue}): now {@code DISPATCHING} and
 * leased until {@code leaseUntil}, which also fences the outcome updates ({@link NotificationRepository#reschedule},
 * {@link NotificationRepository#close}). {@code reclaimed} is true when the previous holder's lease was still set, i.e.
 * it never persisted an outcome (crashed or too slow); a due retry is a normal claim. {@code previousState} is the state
 * before the claim. {@code dueAt} is the original due time (never moved). {@code fallbackPayload} is the opaque JSON
 * text for the webhook; it and {@code userId} are left out of {@link #toString()}.
 */
public record ClaimedNotification(
        UUID id,
        String appId,
        String userId,
        FallbackPolicy policy,
        String fallbackPayload,
        Instant dueAt,
        int fallbackAttempts,
        NotificationState previousState,
        boolean reclaimed,
        Instant leaseUntil) {

    @Override
    public String toString() {
        return "ClaimedNotification[id=" + id + ", appId=" + appId + ", policy=" + policy + ", dueAt=" + dueAt
                + ", fallbackAttempts=" + fallbackAttempts
                + ", previousState=" + previousState + ", reclaimed=" + reclaimed + ", leaseUntil=" + leaseUntil + "]";
    }
}
