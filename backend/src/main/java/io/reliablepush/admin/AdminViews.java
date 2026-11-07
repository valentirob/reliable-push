package io.reliablepush.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response shapes of the support-lookup routes (spec 4). Field names are the API contract. */
final class AdminViews {

    private AdminViews() {}

    /** One row of the notification search. */
    record NotificationItem(
            UUID trackingId,
            String externalId,
            String userId,
            String state,
            String policy,
            Instant createdAt,
            Instant deliveredAt,
            Instant readAt,
            Instant closedAt,
            int fallbackAttempts) {}

    record NotificationPage(List<NotificationItem> items, String nextCursor) {}

    /** The list fields plus the timing fields only the detail shows. */
    record NotificationDetail(
            UUID trackingId,
            String externalId,
            String userId,
            String state,
            String policy,
            Instant createdAt,
            Instant deliveredAt,
            Instant readAt,
            Instant closedAt,
            int fallbackAttempts,
            int timeoutMinutes,
            Instant dueAt) {}

    record Event(String type, Instant occurredAt, Instant receivedAt, boolean late, UUID deviceId, String platform) {}

    record Attempt(int attempt, Instant startedAt, long durationMs, String outcome, Integer httpStatus, String reason) {}

    record Detail(NotificationDetail notification, List<Event> events, List<Attempt> attempts, int payloadBytes) {}

    record Device(UUID deviceId, String platform, Instant createdAt, Instant lastSeenAt, Instant revokedAt) {}

    record Devices(List<Device> items) {}
}
