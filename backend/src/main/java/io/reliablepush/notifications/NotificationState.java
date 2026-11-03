package io.reliablepush.notifications;

/**
 * Lifecycle states (spec 2). {@code DISPATCHING} is internal (fallback claimed, webhook in progress or awaiting a
 * retry) and reported to senders as {@code FALLBACK_PENDING}.
 */
public enum NotificationState {
    PENDING,
    DELIVERED,
    READ,
    DISPATCHING,
    FALLBACK_TRIGGERED,
    FALLBACK_FAILED,
    FALLBACK_EXPIRED,
    CANCELLED;

    /** Final states: nothing changes them any more (rows get {@code closed_at} and no {@code due_at}). */
    public boolean closed() {
        return switch (this) {
            case PENDING, DELIVERED, DISPATCHING -> false;
            case READ, FALLBACK_TRIGGERED, FALLBACK_FAILED, FALLBACK_EXPIRED, CANCELLED -> true;
        };
    }

    /** The name used in the sender API. */
    public String external() {
        return this == DISPATCHING ? "FALLBACK_PENDING" : name();
    }

    /** Parses an API state name; {@code DISPATCHING} is not an API name. */
    public static NotificationState fromExternal(String name) {
        if ("FALLBACK_PENDING".equals(name)) {
            return DISPATCHING;
        }
        NotificationState state = valueOf(name);
        if (state == DISPATCHING) {
            throw new IllegalArgumentException("unknown state");
        }
        return state;
    }
}
