package io.reliablepush.shared.ratelimit;

import io.reliablepush.apps.AppRegistry;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.security.SenderPrincipal;
import io.reliablepush.shared.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-caller rate limiting, after authentication. Today only the sender bucket {@code sender:<appId>} is wired
 * (limit: the app's {@code rate-limits.sender-per-minute}); a request without an authenticated sender is not
 * limited here. The {@code device:<deviceId>} bucket is added with the device filter. Over the limit: 429 problem
 * with {@code Retry-After} and a {@code rate_limit.exceeded} WARN.
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
        return !(request.getAttribute(SenderPrincipal.ATTR) instanceof SenderPrincipal);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String appId = ((SenderPrincipal) request.getAttribute(SenderPrincipal.ATTR)).appId();
        int perMinute = apps.byId(appId).orElseThrow().rateLimits().senderPerMinute();
        String bucket = "sender:" + appId;
        if (limiter.tryConsume(bucket, perMinute)) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = limiter.retryAfterSeconds(bucket);
        EventLog.warn("rate_limit.exceeded", Map.of("event.outcome", "failure", "rp.app_id", appId, "rate_limit.scope", "sender"));
        problems.write(
                request,
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                "Rate limit exceeded; retry after " + retryAfter + " seconds.",
                Map.of(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter)));
    }
}
