package io.reliablepush.notifications;

import java.util.List;

/** One page of {@code GET /v1/notifications}; {@code nextCursor} is null on the last page. */
public record NotificationPage(List<NotificationView> items, String nextCursor) {}
