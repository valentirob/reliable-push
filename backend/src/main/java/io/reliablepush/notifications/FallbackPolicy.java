package io.reliablepush.notifications;

/** When a notification counts as handled: delivered (or read) vs. read only. */
public enum FallbackPolicy {
    NOT_DELIVERED,
    NOT_READ
}
