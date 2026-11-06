package io.reliablepush.admin;

import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.notifications.NotificationState;
import io.reliablepush.notifications.PageCursor;
import io.reliablepush.shared.web.BadParameterException;
import io.reliablepush.shared.web.InvalidRequestException;
import io.reliablepush.shared.web.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only ops dashboard API. {@link AdminKeyFilter} has authenticated every request before it gets here. */
@RestController
@ConditionalOnProperty("reliable-push.admin.keys[0].id")
@RequestMapping("/v1/admin")
class AdminController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    /** One body for every "cannot see it" case: unknown, malformed, other app, missing permission. */
    private static final String NOT_FOUND = "Not found.";

    record Me(String keyId, List<String> apps, List<String> permissions) {}

    record Overviews(List<OverviewQuery.Overview> items) {}

    private final AdminQueries queries;
    private final OverviewQuery overviews;
    private final DailyStatsRepository dailyStats;
    private final ReliablePushProperties properties;
    private final Clock clock;

    AdminController(
            AdminQueries queries,
            OverviewQuery overviews,
            DailyStatsRepository dailyStats,
            ReliablePushProperties properties,
            Clock clock) {
        this.queries = queries;
        this.overviews = overviews;
        this.dailyStats = dailyStats;
        this.properties = properties;
        this.clock = clock;
    }

    private static final int DEFAULT_TREND_DAYS = 30;
    private static final int MAX_TREND_DAYS = 400;

    record TrendDay(
            String day,
            int registered,
            int cancelled,
            int delivered,
            int read,
            int deliveredIos,
            int deliveredAndroid,
            int fallbackTriggered,
            int fallbackFailed,
            int fallbackExpired,
            Long deliveryP50Ms,
            Long deliveryP95Ms,
            Double deliveryRate,
            Double readRate,
            Double fallbackRate,
            boolean live) {}

    record Trend(String appId, List<TrendDay> days) {}

    /**
     * Stored days older than the rollup window, then the window days and today computed live (spec 4); days without a
     * stored row (before the first rollup) are omitted.
     */
    @GetMapping("/stats/daily")
    Trend dailyStats(
            HttpServletRequest request,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        AdminPrincipal principal = principal(request);
        String appId = visibleApp(principal, app);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate parsedTo = parseDate("to", to);
        LocalDate parsedFrom = parseDate("from", from);
        LocalDate end = parsedTo != null ? parsedTo : today.plusDays(1);
        LocalDate start = parsedFrom != null ? parsedFrom : end.minusDays(DEFAULT_TREND_DAYS);
        if (!start.isBefore(end)) {
            throw new BadParameterException("to", "must be after from");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > MAX_TREND_DAYS) {
            throw new BadParameterException("from", "span must not exceed " + MAX_TREND_DAYS + " days");
        }
        LocalDate liveFrom = today.minusDays(RollupWindow.width(properties.retentionDays()));
        List<TrendDay> days = new ArrayList<>();
        LocalDate storedEnd = end.isBefore(liveFrom) ? end : liveFrom;
        if (start.isBefore(storedEnd)) {
            dailyStats.range(appId, start, storedEnd).forEach(d -> days.add(trendDay(d, false)));
        }
        LocalDate liveStart = start.isAfter(liveFrom) ? start : liveFrom;
        LocalDate liveEnd = end.isBefore(today.plusDays(1)) ? end : today.plusDays(1);
        for (LocalDate day = liveStart; day.isBefore(liveEnd); day = day.plusDays(1)) {
            days.add(trendDay(dailyStats.live(appId, day), true));
        }
        return new Trend(appId, days);
    }

    private static TrendDay trendDay(DailyStats d, boolean live) {
        Counts c = d.counts();
        return new TrendDay(
                d.day().toString(),
                c.registered(),
                c.cancelled(),
                c.delivered(),
                c.read(),
                d.deliveredIos(),
                d.deliveredAndroid(),
                c.fallbackTriggered(),
                c.fallbackFailed(),
                c.fallbackExpired(),
                d.deliveryP50Ms(),
                d.deliveryP95Ms(),
                Rates.of(c.delivered(), c),
                Rates.of(c.read(), c),
                Rates.of(c.fallbackTriggered(), c),
                live);
    }

    private static LocalDate parseDate(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadParameterException(field, "must be a date (YYYY-MM-DD)");
        }
    }

    @GetMapping("/overview")
    Overviews overview(
            HttpServletRequest request,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String window) {
        AdminPrincipal principal = principal(request);
        List<String> apps;
        if (app == null) {
            apps = principal.apps().stream().sorted().toList();
        } else {
            apps = List.of(visibleApp(principal, app));
        }
        OverviewQuery.Window parsed = window == null ? OverviewQuery.Window.H24 : OverviewQuery.Window.parse(window);
        if (parsed == null) {
            throw new BadParameterException("window", "must be one of 1h, 24h, 7d");
        }
        return new Overviews(overviews.overview(apps, parsed));
    }

    @GetMapping("/me")
    Me me(HttpServletRequest request) {
        AdminPrincipal principal = principal(request);
        return new Me(
                principal.keyId(),
                principal.apps().stream().sorted().toList(),
                principal.payload() ? List.of("payload") : List.of());
    }

    @GetMapping("/notifications")
    AdminViews.NotificationPage search(
            HttpServletRequest request,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String externalId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        AdminPrincipal principal = principal(request);
        String appId = visibleApp(principal, app);
        nonEmpty("userId", userId);
        nonEmpty("externalId", externalId);
        NotificationState parsedState = parseState(state);
        Instant parsedFrom = parseInstant("from", from);
        Instant parsedTo = parseInstant("to", to);
        if (parsedFrom != null && parsedTo != null && !parsedFrom.isBefore(parsedTo)) {
            throw new BadParameterException("to", "must be after from");
        }
        int pageSize = parseLimit(limit);
        PageCursor after = parseCursor(cursor);

        List<AdminViews.NotificationItem> rows = queries.search(
                appId, userId, externalId, parsedState, parsedFrom, parsedTo, after, pageSize + 1);
        boolean more = rows.size() > pageSize;
        List<AdminViews.NotificationItem> items = more ? rows.subList(0, pageSize) : rows;
        String next = null;
        if (more) {
            AdminViews.NotificationItem last = items.get(items.size() - 1);
            next = new PageCursor(last.createdAt(), last.trackingId()).encode();
        }
        AdminAudit.search(principal, appId, filterNames(userId, externalId, state, from, to));
        return new AdminViews.NotificationPage(items, next);
    }

    @GetMapping("/notifications/{trackingId}")
    AdminViews.Detail notification(HttpServletRequest request, @PathVariable String trackingId) {
        AdminPrincipal principal = principal(request);
        UUID id = parseId(trackingId);
        AdminQueries.Found found = queries.find(id)
                .filter(f -> principal.canSee(f.appId()))
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        AdminViews.Detail detail = queries.detail(found.notification());
        AdminAudit.viewed(principal, found.appId(), id, found.notification().userId());
        return detail;
    }

    @GetMapping(path = "/notifications/{trackingId}/payload", produces = MediaType.APPLICATION_JSON_VALUE)
    String payload(HttpServletRequest request, @PathVariable String trackingId) {
        AdminPrincipal principal = principal(request);
        if (!principal.payload()) {
            throw new NotFoundException(NOT_FOUND);
        }
        UUID id = parseId(trackingId);
        AdminQueries.Found found = queries.find(id)
                .filter(f -> principal.canSee(f.appId()))
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        String payload = queries.payload(id).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        AdminAudit.payloadViewed(principal, found.appId(), id, found.notification().userId());
        return payload;
    }

    @GetMapping("/devices")
    AdminViews.Devices devices(
            HttpServletRequest request,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String userId) {
        AdminPrincipal principal = principal(request);
        String appId = visibleApp(principal, app);
        if (userId == null || userId.isEmpty()) {
            throw new BadParameterException("userId", "is required");
        }
        AdminViews.Devices devices = new AdminViews.Devices(queries.devices(appId, userId));
        AdminAudit.devicesViewed(principal, appId, userId);
        return devices;
    }

    private static AdminPrincipal principal(HttpServletRequest request) {
        return (AdminPrincipal) request.getAttribute(AdminPrincipal.ATTR);
    }

    /** {@code app} is required (400); an app the key may not see is a 404 like an unknown one. */
    private static String visibleApp(AdminPrincipal principal, String app) {
        if (app == null || app.isEmpty()) {
            throw new BadParameterException("app", "is required");
        }
        if (!principal.canSee(app)) {
            throw new NotFoundException(NOT_FOUND);
        }
        return app;
    }

    /** A malformed id cannot exist: 404, like an unknown one. */
    private static UUID parseId(String trackingId) {
        try {
            return UUID.fromString(trackingId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(NOT_FOUND);
        }
    }

    private static void nonEmpty(String field, String value) {
        if (value != null && value.isEmpty()) {
            throw new BadParameterException(field, "must not be empty");
        }
    }

    private static NotificationState parseState(String state) {
        if (state == null) {
            return null;
        }
        try {
            return NotificationState.fromExternal(state);
        } catch (IllegalArgumentException e) {
            throw new BadParameterException("state", "is not a notification state");
        }
    }

    private static Instant parseInstant(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            Instant parsed = OffsetDateTime.parse(value).toInstant();
            if (PageCursor.inSaneRange(parsed)) {
                return parsed;
            }
        } catch (DateTimeParseException e) {
            // falls through to the common rejection
        }
        throw new BadParameterException(field, "must be an ISO-8601 date-time between years 1970 and 9999");
    }

    private static int parseLimit(String limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int parsed = Integer.parseInt(limit);
            if (parsed >= 1 && parsed <= MAX_LIMIT) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // falls through to the common rejection
        }
        throw new BadParameterException("limit", "must be an integer between 1 and " + MAX_LIMIT);
    }

    private static PageCursor parseCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            return PageCursor.decode(cursor);
        } catch (InvalidRequestException e) {
            throw new BadParameterException("cursor", e.reason());
        }
    }

    private static Set<String> filterNames(String userId, String externalId, String state, String from, String to) {
        Set<String> names = new TreeSet<>();
        if (userId != null) names.add("userId");
        if (externalId != null) names.add("externalId");
        if (state != null) names.add("state");
        if (from != null) names.add("from");
        if (to != null) names.add("to");
        return names;
    }
}
