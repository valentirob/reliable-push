package io.reliablepush.admin;

/**
 * Notification counts of one app over a period (spec 3.2 columns; a live window or a rollup day).
 * {@code noDeviceRegistered} counts the rows stored as {@code NO_DEVICE_REGISTERED} (sidecar revision spec 2.2).
 */
record Counts(
        int registered,
        int cancelled,
        int noDeviceRegistered,
        int delivered,
        int read,
        int fallbackTriggered,
        int fallbackFailed,
        int fallbackExpired) {}
