package io.reliablepush.admin;

import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.ratelimit.RateLimiter;
import io.reliablepush.shared.web.ProblemWriter;
import io.reliablepush.shared.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the read-only admin API ({@code /v1/admin} and below) with the {@code X-Admin-Key} header. Without
 * configured admin keys the whole surface answers 404 (it does not exist). A missing and an unknown key get the very
 * same 401 problem; only the logged {@code reason} differs and the key is never logged. After authentication the
 * request is limited per key ({@code admin:<keyId>}, {@code reliable-push.admin.rate-limit-per-minute}) and the
 * principal is published as {@link AdminPrincipal#ATTR}. Every response, success or error, carries
 * {@code Cache-Control: no-store}.
 */
public class AdminKeyFilter extends OncePerRequestFilter {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";
    static final String PROTECTED_PREFIX = "/v1/admin";

    private final AdminKeys keys;
    private final RateLimiter limiter;
    private final ProblemWriter problems;

    public AdminKeyFilter(AdminKeys keys, RateLimiter limiter, ProblemWriter problems) {
        this.keys = keys;
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.isUnder(request, PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!keys.enabled()) {
            chain.doFilter(request, response); // nothing is mapped when disabled: the normal not-found handling answers
            return;
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String key = request.getHeader(ADMIN_KEY_HEADER);
        if (key == null || key.isEmpty()) {
            reject(request, response, "missing_admin_key");
            return;
        }
        Optional<AdminPrincipal> principal = keys.byRawKey(key);
        if (principal.isEmpty()) {
            reject(request, response, "unknown_admin_key");
            return;
        }
        String keyId = principal.get().keyId();
        String bucket = "admin:" + keyId;
        if (!limiter.tryConsume(bucket, keys.ratePerMinute())) {
            long retryAfter = limiter.retryAfterSeconds(bucket);
            EventLog.warn("rate_limit.exceeded", Map.of(
                    "event.outcome", "failure", "rate_limit.scope", "admin", "rp.admin_key", keyId));
            problems.write(
                    request,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too Many Requests",
                    "Rate limit exceeded; retry after " + retryAfter + " seconds.",
                    Map.of(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter)));
            return;
        }
        request.setAttribute(AdminPrincipal.ATTR, principal.get());
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        EventLog.warn("auth.failed", Map.of("event.outcome", "failure", "reason", reason));
        problems.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                "A valid admin key is required.",
                Map.of(HttpHeaders.WWW_AUTHENTICATE, "AdminKey realm=\"reliable-push\""));
    }
}
