package io.reliablepush.admin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The days a rollup recomputes (spec 3.2): {@code today - W .. today - 1}, {@code W = min(7, retention-days)}. */
final class RollupWindow {

    private static final int MAX_DAYS = 7;

    private RollupWindow() {}

    /** Number of recomputed days: a notification can stay open up to 7 days, and never longer than the retention. */
    static int width(int retentionDays) {
        return Math.min(MAX_DAYS, retentionDays);
    }

    static List<LocalDate> days(LocalDate today, int retentionDays) {
        int w = width(retentionDays);
        List<LocalDate> days = new ArrayList<>(w);
        for (int i = w; i >= 1; i--) {
            days.add(today.minusDays(i));
        }
        return List.copyOf(days);
    }
}
