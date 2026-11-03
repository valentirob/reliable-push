package io.reliablepush.shared.security;

import io.reliablepush.devices.DeviceClaims;
import io.reliablepush.devices.DeviceService;
import io.reliablepush.devices.DeviceTokenService;
import io.reliablepush.devices.InvalidDeviceTokenException;
import io.reliablepush.shared.logging.AccessLogFilter;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.web.ProblemWriter;
import io.reliablepush.shared.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the device API: {@code /v1/devices/token:refresh}, {@code /v1/devices/me} and {@code /v1/events}.
 * {@code Authorization: Bearer <device token>} is verified ({@link DeviceTokenService}: signature, issuer, expiry) and
 * then checked against the device row ({@link DeviceService#isActive}: exists for the token's app and user, not
 * revoked, same {@code token_version}; positive answers cached for {@code revocation-cache-ttl}, keyed by device id and
 * version, never by token). On success the {@link DevicePrincipal} is exposed as request attribute
 * {@link DevicePrincipal#ATTR} plus {@code rp.app_id} and {@code rp.device_id} for the access log.
 *
 * <p>Every failure gets the very same 401 problem ({@code WWW-Authenticate: Bearer}); only the logged {@code reason}
 * code differs. The token is never logged.
 */
public class DeviceAuthFilter extends OncePerRequestFilter {

    static final List<String> PROTECTED_PATHS = List.of("/v1/devices/token:refresh", "/v1/devices/me", "/v1/events");
    static final String CHALLENGE = "Bearer realm=\"reliable-push\"";
    private static final String BEARER = "bearer ";
    private static final String REVOKED = "revoked";
    private static final String MISSING_TOKEN = "missing_token";

    private final DeviceTokenService tokens;
    private final DeviceService devices;
    private final ProblemWriter problems;

    public DeviceAuthFilter(DeviceTokenService tokens, DeviceService devices, ProblemWriter problems) {
        this.tokens = tokens;
        this.devices = devices;
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
            claims = tokens.verify(token);
        } catch (InvalidDeviceTokenException e) {
            reject(request, response, e.reason());
            return;
        }
        if (!devices.isActive(claims)) {
            reject(request, response, REVOKED);
            return;
        }
        DevicePrincipal principal =
                new DevicePrincipal(claims.appId(), claims.userId(), claims.deviceId(), claims.tokenVersion());
        request.setAttribute(DevicePrincipal.ATTR, principal);
        request.setAttribute(AccessLogFilter.APP_ID_ATTRIBUTE, principal.appId());
        request.setAttribute(AccessLogFilter.DEVICE_ID_ATTRIBUTE, principal.deviceId().toString());
        chain.doFilter(request, response);
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
