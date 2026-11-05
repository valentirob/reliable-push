package io.reliablepush.client;

import java.time.Instant;
import java.util.UUID;

/** A tracked notification as returned by the sender API. Nullable: externalId, dueAt, deliveredAt, readAt, closedAt. */
public record Notification(UUID trackingId, NotificationState state, String userId, String externalId,
                           FallbackPolicy policy, int timeoutMinutes, Instant createdAt, Instant dueAt,
                           Instant deliveredAt, Instant readAt, Instant closedAt) {
}
