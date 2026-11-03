package io.reliablepush.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.status.StatusLogger;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Emits one {@code api.request} event per HTTP request (spec 9.1). Runs right after
 * {@link TraceIdFilter} so it wraps the auth filters and can read the {@code rp.app_id} request
 * attribute they set.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    public static final String APP_ID_ATTRIBUTE = "rp.app_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            // the container turns this into a 500 only after the filter chain has unwound
            try {
                log(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, System.nanoTime() - start);
            } catch (RuntimeException loggingFailure) {
                // the original exception must reach the container unchanged
                e.addSuppressed(loggingFailure);
            }
            throw e;
        }
        logSafely(request, response, response.getStatus(), System.nanoTime() - start);
    }

    /** Logging must never break request handling: a failure here is reported to Log4j's status logger only. */
    private void logSafely(HttpServletRequest request, HttpServletResponse response, int status, long nanos) {
        try {
            log(request, response, status, nanos);
        } catch (RuntimeException e) {
            StatusLogger.getLogger().error("api.request access log failed", e);
        }
    }

    private void log(HttpServletRequest request, HttpServletResponse response, int status, long nanos) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("http.request.method", request.getMethod());
        fields.put("url.path", pathOf(request));
        fields.put("http.response.status_code", status);
        fields.put("event.duration", nanos);
        fields.put("event.outcome", status < 400 ? "success" : "failure");
        Object appId = request.getAttribute(APP_ID_ATTRIBUTE);
        if (appId != null) {
            fields.put("rp.app_id", appId.toString());
        }
        emit(fields);
    }

    /** Seam for tests: writes the {@code api.request} event. */
    void emit(Map<String, Object> fields) {
        EventLog.info("api.request", fields);
    }

    /** The matched route template when Spring resolved one, else the raw path (no query string). */
    private static String pathOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }
}
