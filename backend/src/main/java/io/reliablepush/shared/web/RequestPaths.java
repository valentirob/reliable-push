package io.reliablepush.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Path matching for the security filters. The container normalises the servlet path (decodes it, drops path
 * parameters such as {@code ;x=1}, collapses dot segments) while {@code getRequestURI()} is the raw target, and
 * Spring may route on either. A filter therefore applies when <em>either</em> form is under the prefix, so no
 * spelling of a protected URL slips past it (fail closed).
 */
public final class RequestPaths {

    private RequestPaths() {}

    /** True for {@code prefix} itself and everything below it ({@code prefix + "/..."}). */
    public static boolean isUnder(HttpServletRequest request, String prefix) {
        return isUnder(request.getRequestURI(), prefix) || isUnder(request.getServletPath(), prefix);
    }

    private static boolean isUnder(String path, String prefix) {
        if (path == null || !path.startsWith(prefix)) {
            return false;
        }
        if (path.length() == prefix.length()) {
            return true;
        }
        char next = path.charAt(prefix.length());
        return next == '/' || next == ';';
    }
}
