package io.reliablepush.harness.console;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guard for the console API. Every request under {@code /console/api/} must carry a Host of {@link #ALLOWED_HOSTS}
 * (403 otherwise, the value is not echoed): a DNS-rebound page reaches the harness under its own name and would
 * otherwise pass the same-origin checks. Then a state-changing request ({@code POST}, {@code PUT}, {@code PATCH},
 * {@code DELETE}) under {@code /console/api/} is rejected with 403 when the browser says it comes from another site
 * ({@code Sec-Fetch-Site} present and not {@code same-origin}, or {@code Origin} present and not this request's own
 * origin), and with 415 unless its {@code Content-Type} is {@code application/json} (a cross-site page cannot send that
 * without a CORS preflight, which the harness never allows). Scripts such as {@code curl} send neither header and pass.
 * The fallback receiver ({@code /hooks/}) is not covered: the backend calls it.
 */
@Component
public class ConsoleRequestGuard extends OncePerRequestFilter {

    static final String PREFIX = "/console/api/";
    private static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Hosts the console may be addressed by (DNS-rebinding guard): loopback, the emulator's host alias, the compose service. */
    static final Set<String> ALLOWED_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "10.0.2.2", "harness");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!allowedHost(request.getHeader("Host"))) {
            reject(response, HttpStatus.FORBIDDEN, "Unexpected host", "the console API answers only to local host names");
            return;
        }
        if (!UNSAFE.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            chain.doFilter(request, response);
            return;
        }
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equalsIgnoreCase("same-origin")) {
            reject(response, HttpStatus.FORBIDDEN, "Cross-site request", "the console API accepts same-origin requests only");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.equalsIgnoreCase(ownOrigin(request))) {
            reject(response, HttpStatus.FORBIDDEN, "Cross-site request", "the console API accepts same-origin requests only");
            return;
        }
        if (!isJson(request.getContentType())) {
            reject(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type",
                    "console API requests need Content-Type: application/json (send {} when there is nothing to say)");
            return;
        }
        chain.doFilter(request, response);
    }

    /** The host part of the Host header (port ignored, case-insensitive) must be in {@link #ALLOWED_HOSTS}. */
    static boolean allowedHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String h = hostHeader.strip().toLowerCase(Locale.ROOT);
        int end = h.startsWith("[") ? h.indexOf(']') + 1 : (h.contains(":") ? h.lastIndexOf(':') : h.length());
        return end > 0 && ALLOWED_HOSTS.contains(h.substring(0, end));
    }

    /** {@code scheme://host[:port]} as the client addressed this server (the Host header). */
    static String ownOrigin(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                    || ("https".equals(request.getScheme()) && port == 443);
            host = request.getServerName() + (defaultPort ? "" : ":" + port);
        }
        return request.getScheme() + "://" + host.strip();
    }

    private static boolean isJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return type.getType().equalsIgnoreCase("application") && type.getSubtype().equalsIgnoreCase("json");
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\"}");
    }
}
