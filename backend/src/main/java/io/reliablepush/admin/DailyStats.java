package io.reliablepush.admin;

import java.time.LocalDate;

/** One app and UTC day of {@code daily_stats} (spec 3.2), stored or computed live. */
record DailyStats(
        LocalDate day,
        String appId,
        Counts counts,
        int deliveredIos,
        int deliveredAndroid,
        Long deliveryP50Ms,
        Long deliveryP95Ms) {}
