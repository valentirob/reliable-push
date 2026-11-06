package io.reliablepush.shared.security;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.shared.logging.AccessLogFilter;
import io.reliablepush.shared.logging.EventLog;
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
 * Authenticates the sender API ({@code /v1/notifications} and below): the {@code X-Api-Key} header is looked up
 * through {@link AppRegistry#bySenderApiKey} (hash comparison in constant time). On success the app id is exposed as
 * request attributes {@code rp.app_id} (access log) and {@link SenderPrincipal#ATTR}. A missing and an unknown key
 * get the very same 401 problem, so the response does not reveal whether a key exists; only the logged
 * {@code reason} code differs. The key itself is never logged.
 */
public class SenderApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";
    static final String PROTECTED_PREFIX = "/v1/notifications";

    private final AppRegistry apps;
    private final ProblemWriter problems;

    public SenderApiKeyFilter(AppRegistry apps, ProblemWriter problems) {
        this.apps = apps;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.isUnder(request, PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(API_KEY_HEADER);
        if (key == null || key.isBlank()) {
            reject(request, response, "missing_key");
            return;
        }
        Optional<AppConfig> app = apps.bySenderApiKey(key);
        if (app.isEmpty()) {
            reject(request, response, "unknown_key");
            return;
        }
        String appId = app.get().appId();
        request.setAttribute(AccessLogFilter.APP_ID_ATTRIBUTE, appId);
        request.setAttribute(SenderPrincipal.ATTR, new SenderPrincipal(appId));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        EventLog.warn("auth.failed", Map.of("event.outcome", "failure", "reason", reason));
        problems.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                "A valid API key is required.",
                Map.of(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"reliable-push\""));
    }
}
