package io.reliablepush.shared.ratelimit;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.security.DevicePrincipal;
import io.reliablepush.shared.security.SenderPrincipal;
import io.reliablepush.shared.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-caller rate limiting, after authentication: {@code sender:<appId>} for an authenticated sender (limit: the app's
 * {@code rate-limits.sender-per-minute}) and {@code device:<deviceId>} for an authenticated device (limit:
 * {@code rate-limits.device-per-minute}). A request with neither is not limited here (device registration is limited
 * per app inside the devices module, as the app is only known after parsing the body). Over the limit: 429 problem with
 * {@code Retry-After} and a {@code rate_limit.exceeded} WARN.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final AppRegistry apps;
    private final ProblemWriter problems;

    public RateLimitFilter(RateLimiter limiter, AppRegistry apps, ProblemWriter problems) {
        this.limiter = limiter;
        this.apps = apps;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getAttribute(SenderPrincipal.ATTR) instanceof SenderPrincipal)
                && !(request.getAttribute(DevicePrincipal.ATTR) instanceof DevicePrincipal);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String appId;
        String bucket;
        boolean sender = request.getAttribute(SenderPrincipal.ATTR) instanceof SenderPrincipal;
        Map<String, String> fields = new HashMap<>();
        if (request.getAttribute(SenderPrincipal.ATTR) instanceof SenderPrincipal principal) {
            appId = principal.appId();
            bucket = "sender:" + appId;
            fields.put("rate_limit.scope", "sender");
        } else {
            DevicePrincipal device = (DevicePrincipal) request.getAttribute(DevicePrincipal.ATTR);
            appId = device.appId();
            bucket = "device:" + device.deviceId();
            fields.put("rate_limit.scope", "device");
            fields.put("rp.device_id", device.deviceId().toString());
        }
        Optional<AppConfig> app = apps.byId(appId);
        if (app.isEmpty()) {
            // the authentication filters only admit configured apps; never answer 500 if that ever changes
            unknownApp(request, response, sender);
            return;
        }
        int perMinute = sender ? app.get().rateLimits().senderPerMinute() : app.get().rateLimits().devicePerMinute();
        if (limiter.tryConsume(bucket, perMinute)) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = limiter.retryAfterSeconds(bucket);
        fields.put("event.outcome", "failure");
        fields.put("rp.app_id", appId);
        EventLog.warn("rate_limit.exceeded", fields);
        problems.write(
                request,
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                "Rate limit exceeded; retry after " + retryAfter + " seconds.",
                Map.of(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter)));
    }

    private void unknownApp(HttpServletRequest request, HttpServletResponse response, boolean sender) throws IOException {
        EventLog.warn("auth.failed", Map.of("event.outcome", "failure", "reason", "unknown_app"));
        problems.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                sender ? "A valid API key is required." : "A valid device token is required.",
                Map.of(HttpHeaders.WWW_AUTHENTICATE,
                        sender ? "ApiKey realm=\"reliable-push\"" : "Bearer realm=\"reliable-push\""));
    }
}
