package io.reliablepush.notifications;

import java.time.Instant;
import java.util.UUID;

/**
 * A notification row (spec 3). {@code fallbackPayload} is the opaque JSON text for the fallback webhook; it never
 * leaves the service through the sender API.
 */
public record Notification(
        UUID id,
        String appId,
        String userId,
        String externalId,
        FallbackPolicy policy,
        int timeoutMinutes,
        String fallbackPayload,
        NotificationState state,
        Instant createdAt,
        Instant dueAt,
        Instant deliveredAt,
        Instant readAt,
        Instant closedAt) {}
