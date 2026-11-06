package io.reliablepush.client;

/** When the fallback fires: {@code NOT_DELIVERED} (no delivered/read event by the deadline) or {@code NOT_READ}. */
public enum FallbackPolicy {
    NOT_DELIVERED, NOT_READ
}
