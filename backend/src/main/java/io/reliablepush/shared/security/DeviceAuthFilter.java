package io.reliablepush.shared.security;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.devices.DeviceClaims;
import io.reliablepush.devices.DeviceService;
import io.reliablepush.devices.DeviceTokenService;
import io.reliablepush.devices.InvalidDeviceTokenException;
import io.reliablepush.shared.logging.AccessLogFilter;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.web.DatabaseUnavailable;
import io.reliablepush.shared.web.ProblemWriter;
import io.reliablepush.shared.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the device API: {@code /v1/devices/token:refresh}, {@code /v1/devices/me} and {@code /v1/events}.
 * {@code Authorization: Bearer <device token>} is verified ({@link DeviceTokenService}: signature, issuer, expiry),
 * its app ({@code aud}) must still be configured ({@link AppRegistry}; reason {@code unknown_app} otherwise), and it is
 * then checked against the device row ({@link DeviceService#isActive}: exists for the token's app and user, not
 * revoked, same {@code token_version}; positive answers cached for {@code revocation-cache-ttl}, keyed by device id and
 * version, never by token). On success the {@link DevicePrincipal} is exposed as request attribute
 * {@link DevicePrincipal#ATTR} (with {@code inGrace} for an expired token accepted under the app's
 * {@code device-token-events-grace}, which only {@code POST /v1/events} and {@code DELETE /v1/devices/me} allow,
 * matched exactly) plus {@code rp.app_id} and {@code rp.device_id} for the access log.
 *
 * <p>A connection-pool timeout during the device lookup is answered 503 with {@code Retry-After}
 * ({@link DatabaseUnavailable}). Every authentication failure gets the very same 401 problem
 * ({@code WWW-Authenticate: Bearer}); only the logged {@code reason} code differs. The token is never logged.
 */
public class DeviceAuthFilter extends OncePerRequestFilter {

    static final List<String> PROTECTED_PATHS = List.of("/v1/devices/token:refresh", "/v1/devices/me", "/v1/events");
    private static final String EVENTS_PATH = "/v1/events";
    private static final String ME_PATH = "/v1/devices/me";
    static final String CHALLENGE = "Bearer realm=\"reliable-push\"";
    private static final String BEARER = "bearer ";
    private static final String REVOKED = "revoked";
    private static final String MISSING_TOKEN = "missing_token";
    private static final String UNKNOWN_APP = "unknown_app";

    private final DeviceTokenService tokens;
    private final DeviceService devices;
    private final AppRegistry apps;
    private final ProblemWriter problems;

    public DeviceAuthFilter(DeviceTokenService tokens, DeviceService devices, AppRegistry apps, ProblemWriter problems) {
        this.tokens = tokens;
        this.devices = devices;
        this.apps = apps;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PROTECTED_PATHS.stream().noneMatch(path -> RequestPaths.isUnder(request, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER.length() || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            reject(request, response, MISSING_TOKEN);
            return;
        }
        String token = header.substring(BEARER.length()).trim();
        DeviceClaims claims;
        try {
            claims = tokens.verify(token, appId -> graceFor(request, appId));
        } catch (InvalidDeviceTokenException e) {
            reject(request, response, e.reason());
            return;
        }
        if (apps.byId(claims.appId()).isEmpty()) {
            // validly signed, but its app was removed from the configuration since the token was issued
            reject(request, response, UNKNOWN_APP);
            return;
        }
        boolean active;
        try {
            active = devices.isActive(claims);
        } catch (DataAccessException | TransactionException e) {
            if (!DatabaseUnavailable.isPoolTimeout(e)) {
                throw e;
            }
            // backpressure, answered here: an exception leaving a filter is logged at ERROR by the container
            DatabaseUnavailable.logWarning(e);
            problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, DatabaseUnavailable.TITLE,
                    DatabaseUnavailable.DETAIL,
                    Map.of(HttpHeaders.RETRY_AFTER, Long.toString(DatabaseUnavailable.RETRY_AFTER_SECONDS)));
            return;
        }
        if (!active) {
            reject(request, response, REVOKED);
            return;
        }
        DevicePrincipal principal =
                new DevicePrincipal(claims.appId(), claims.userId(), claims.deviceId(), claims.tokenVersion(),
                        tokens.isInGrace(claims));
        request.setAttribute(DevicePrincipal.ATTR, principal);
        request.setAttribute(AccessLogFilter.APP_ID_ATTRIBUTE, principal.appId());
        request.setAttribute(AccessLogFilter.DEVICE_ID_ATTRIBUTE, principal.deviceId().toString());
        chain.doFilter(request, response);
    }

    /**
     * The expiry grace of the token's app, on exactly two routes: {@code POST /v1/events} and {@code DELETE
     * /v1/devices/me}. Both the raw request URI and the normalised servlet path must equal the route; any other
     * spelling (trailing slash, {@code ;} parameters, dot segments, encodings, case) and every other route, notably
     * {@code token:refresh}, gets zero (fail closed).
     */
    private Duration graceFor(HttpServletRequest request, String appId) {
        if (!isGraceRoute(request)) {
            return Duration.ZERO;
        }
        return apps.byId(appId).map(AppConfig::deviceTokenEventsGrace).orElse(Duration.ZERO);
    }

    static boolean isGraceRoute(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String servletPath = request.getServletPath();
        if (uri == null || !uri.equals(servletPath)) {
            return false;
        }
        return (HttpMethod.POST.matches(method) && EVENTS_PATH.equals(uri))
                || (HttpMethod.DELETE.matches(method) && ME_PATH.equals(uri));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        EventLog.warn("auth.failed", Map.of("event.outcome", "failure", "reason", reason));
        problems.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                "A valid device token is required.",
                Map.of(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE));
    }
}
