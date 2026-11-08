package io.reliablepush.client;

/** Lifecycle state of a tracked notification (external names of the sender API). */
public enum NotificationState {
    PENDING, DELIVERED, READ, FALLBACK_PENDING, FALLBACK_TRIGGERED, FALLBACK_FAILED, FALLBACK_EXPIRED, CANCELLED,
    /** The user had no active device when registered: closed, not tracked, never falls back. */
    NO_DEVICE_REGISTERED,
    /** A state this client version does not know (the server is newer). Never sent to the server. */
    UNKNOWN;

    static NotificationState fromWire(String value) {
        for (NotificationState s : values()) {
            if (s != UNKNOWN && s.name().equals(value)) {
                return s;
            }
        }
        return UNKNOWN;
    }
}
