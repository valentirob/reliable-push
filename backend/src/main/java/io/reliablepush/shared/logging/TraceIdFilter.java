package io.reliablepush.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation id (spec 9.1): the trace-id of a W3C {@code traceparent} header, else a sane
 * {@code X-Request-Id}, else a generated 32-hex id. Put in the {@link ThreadContext} as
 * {@code trace.id} and echoed in the {@code X-Request-Id} response header.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_KEY = "trace.id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Pattern TRACEPARENT =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    // conservative whitelist: the value ends up in a response header and in every log line
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        ThreadContext.put(TRACE_ID_KEY, traceId);
        response.setHeader(REQUEST_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            ThreadContext.clearMap();
        }
    }

    private static String resolveTraceId(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null) {
            Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
            if (matcher.matches() && !ZERO_TRACE_ID.equals(matcher.group(1))) {
                return matcher.group(1);
            }
        }
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches()) {
            return requestId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
