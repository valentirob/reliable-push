package io.reliablepush.notifications;

import java.time.Instant;
import java.util.UUID;

/** A notification as the sender API shows it: external state name, no fallback content. */
public record NotificationView(
        UUID trackingId,
        String userId,
        String externalId,
        String state,
        FallbackPolicy policy,
        int timeoutMinutes,
        Instant createdAt,
        Instant dueAt,
        Instant deliveredAt,
        Instant readAt,
        Instant closedAt) {

    static NotificationView of(Notification n) {
        return new NotificationView(
                n.id(),
                n.userId(),
                n.externalId(),
                n.state().external(),
                n.policy(),
                n.timeoutMinutes(),
                n.createdAt(),
                n.dueAt(),
                n.deliveredAt(),
                n.readAt(),
                n.closedAt());
    }
}
