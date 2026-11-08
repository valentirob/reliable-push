package io.reliablepush.admin;

import io.reliablepush.fallback.circuit.Circuit;
import io.reliablepush.fallback.circuit.CircuitBreaker;
import io.reliablepush.fallback.circuit.CircuitBreakerProperties;
import io.reliablepush.fallback.circuit.CircuitEvent;
import io.reliablepush.fallback.circuit.CircuitRepository;
import io.reliablepush.fallback.circuit.CircuitState;
import io.reliablepush.shared.web.BadParameterException;
import io.reliablepush.shared.web.ConflictException;
import io.reliablepush.shared.web.ForbiddenException;
import io.reliablepush.shared.web.NotFoundException;
import io.reliablepush.shared.web.RetryLaterException;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fallback circuit routes of the admin API (spec 6). {@link AdminKeyFilter} has authenticated and rate-limited
 * every request before it gets here; the resume additionally needs the {@code fallback-control} permission.
 */
@RestController
@ConditionalOnProperty("reliable-push.admin.keys[0].id")
@RequestMapping("/v1/admin/fallback-circuits")
class AdminFallbackController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final String NOT_FOUND = "Not found.";
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final long LOCK_RETRY_AFTER_SECONDS = 5;

    /** {@code since} is {@code null} for an app without a stored circuit; the other times only while FORCED. */
    record CircuitView(
            String appId, CircuitState state, Instant since, Instant forcedUntil, String forcedBy, long suppressed) {}

    record Circuits(boolean suspended, boolean enabled, List<CircuitView> items) {}

    record EventView(
            long id,
            Instant at,
            String transition,
            String reason,
            Long due,
            BigDecimal ratio,
            Long suppressed,
            String adminKeyId) {}

    record EventPage(List<EventView> items, String nextCursor) {}

    private final CircuitRepository circuits;
    private final CircuitBreaker breaker;
    private final CircuitBreakerProperties properties;

    AdminFallbackController(CircuitRepository circuits, CircuitBreaker breaker, CircuitBreakerProperties properties) {
        this.circuits = circuits;
        this.breaker = breaker;
        this.properties = properties;
    }

    @GetMapping
    Circuits list(HttpServletRequest request) {
        AdminPrincipal principal = principal(request);
        List<String> apps = principal.apps().stream().sorted().toList();
        Map<String, Circuit> stored = circuits.findAll(apps);
        List<CircuitView> items = apps.stream().map(app -> view(app, stored.get(app))).toList();
        return new Circuits(properties.suspended(), properties.enabled(), items);
    }

    @GetMapping("/{appId}/events")
    EventPage events(
            HttpServletRequest request,
            @PathVariable String appId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        visibleApp(principal(request), appId);
        int pageSize = parseLimit(limit);
        Long before = parseCursor(cursor);
        List<CircuitEvent> rows = circuits.events(appId, pageSize + 1, before);
        boolean more = rows.size() > pageSize;
        List<CircuitEvent> page = more ? rows.subList(0, pageSize) : rows;
        String next = more ? encode(page.get(page.size() - 1).id()) : null;
        return new EventPage(page.stream().map(AdminFallbackController::eventView).toList(), next);
    }

    /**
     * Forces the circuit (spec 6): {@code 403} without {@code fallback-control}, {@code 404} for an app outside the
     * key's scope, {@code 409} when there is nothing to resume ({@code suspended} or breaker disabled), {@code 503}
     * with {@code Retry-After: 5} when a detector evaluation holds the lock for longer than the force may wait.
     */
    @PostMapping("/{appId}:resume")
    CircuitView resume(HttpServletRequest request, @PathVariable String appId) {
        AdminPrincipal principal = principal(request);
        if (!principal.canControlFallback()) {
            throw new ForbiddenException("This admin key may not resume fallbacks.");
        }
        visibleApp(principal, appId);
        if (properties.suspended()) {
            throw new ConflictException("Fallbacks are suspended by configuration; there is nothing to resume.");
        }
        if (!properties.enabled()) {
            throw new ConflictException("The fallback circuit breaker is disabled; there is nothing to resume.");
        }
        Circuit forced;
        try {
            forced = breaker.force(appId, principal.keyId());
        } catch (DataAccessException e) {
            if (!isLockTimeout(e)) {
                throw e;
            }
            throw RetryLaterException.serviceUnavailable(
                    "A circuit evaluation is in progress; retry in a moment.", LOCK_RETRY_AFTER_SECONDS);
        }
        AdminAudit.fallbackResumed(principal, appId);
        return view(appId, forced);
    }

    /** PostgreSQL's {@code lock_not_available}; Spring leaves it uncategorized for an advisory lock. */
    private static boolean isLockTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** With the breaker disabled every circuit reads as CLOSED (spec 2); the suppressed count stays as stored. */
    private CircuitView view(String appId, Circuit stored) {
        if (stored == null) {
            return new CircuitView(appId, CircuitState.CLOSED, null, null, null, 0);
        }
        if (!properties.enabled()) {
            return new CircuitView(appId, CircuitState.CLOSED, null, null, null, stored.suppressed());
        }
        return new CircuitView(
                appId, stored.state(), stored.since(), stored.forcedUntil(), stored.forcedBy(), stored.suppressed());
    }

    private static EventView eventView(CircuitEvent e) {
        return new EventView(
                e.id(), e.at(), e.transition(), e.reason(), e.due(), e.ratio(), e.suppressed(), e.adminKeyId());
    }

    private static AdminPrincipal principal(HttpServletRequest request) {
        return (AdminPrincipal) request.getAttribute(AdminPrincipal.ATTR);
    }

    /** An app the key may not see is a 404 like an unknown one. */
    private static void visibleApp(AdminPrincipal principal, String appId) {
        if (!principal.canSee(appId)) {
            throw new NotFoundException(NOT_FOUND);
        }
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

    /** The cursor is the id of the last event of the previous page, opaque to the client. */
    private static Long parseCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            long id = Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
            if (id > 0) {
                return id;
            }
        } catch (IllegalArgumentException e) {
            // falls through to the common rejection
        }
        throw new BadParameterException("cursor", "is not a valid cursor");
    }

    private static String encode(long id) {
        byte[] bytes = Long.toString(id).getBytes(StandardCharsets.US_ASCII);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
