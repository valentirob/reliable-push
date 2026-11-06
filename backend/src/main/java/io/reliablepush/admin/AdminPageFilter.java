package io.reliablepush.admin;

import io.reliablepush.shared.web.ProblemWriter;
import io.reliablepush.shared.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gates the static dashboard page ({@code /admin} and below). Without configured admin keys it does nothing (no page is mapped, so it answers like an unknown path). Otherwise
 * it sets the security headers (spec section 5) and forwards {@code /admin} and {@code /admin/} to
 * {@code /admin/index.html}. The page itself needs no key: it holds none and calls the key-protected API.
 */
public class AdminPageFilter extends OncePerRequestFilter {

    static final String PROTECTED_PREFIX = "/admin";
    static final String INDEX = "/admin/index.html";
    static final String CSP = "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private final AdminKeys keys;
    private final ProblemWriter problems;

    public AdminPageFilter(AdminKeys keys, ProblemWriter problems) {
        this.keys = keys;
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
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        String path = request.getServletPath();
        if ("/admin".equals(path) || "/admin/".equals(path)) {
            request.getRequestDispatcher(INDEX).forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
