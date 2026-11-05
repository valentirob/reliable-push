package io.reliablepush.client;

import java.util.List;

/** One page of notifications, newest first; {@code nextCursor} is null on the last page. */
public record NotificationPage(List<Notification> items, String nextCursor) {

    public NotificationPage {
        items = List.copyOf(items);
    }
}
