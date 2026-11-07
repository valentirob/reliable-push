package io.reliablepush.admin;

/** Notification counts of one app over a period (spec 3.2 columns; a live window or a rollup day). */
record Counts(
        int registered,
        int cancelled,
        int delivered,
        int read,
        int fallbackTriggered,
        int fallbackFailed,
        int fallbackExpired) {}
