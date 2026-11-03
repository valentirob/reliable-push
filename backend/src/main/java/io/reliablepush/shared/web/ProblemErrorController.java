package io.reliablepush.shared.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Replaces Boot's {@code BasicErrorController}: failures that never reach {@link ProblemHandler} (an unexpected
 * exception in a servlet filter, errors raised by the container itself) are dispatched to {@code /error} and
 * answered here with the same {@code application/problem+json} shape as {@link ProblemWriter} writes. Status comes
 * from the container; the detail is generic - no exception message and no trace is ever included.
 */
@Controller
public class ProblemErrorController implements ErrorController {

    static final String UNEXPECTED_DETAIL = "An unexpected error occurred.";
    private static final String REQUEST_DETAIL = "The request could not be processed.";

    private final ProblemWriter problems;

    public ProblemErrorController(ProblemWriter problems) {
        this.problems = problems;
    }

    @RequestMapping("/error")
    void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpStatus status = statusOf(request);
        String detail = status.is5xxServerError() ? UNEXPECTED_DETAIL : REQUEST_DETAIL;
        problems.write(request, response, status, status.getReasonPhrase(), detail);
    }

    /** A request that was not forwarded here by the container (no error status attribute) is simply unknown. */
    private static HttpStatus statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer value) {
            HttpStatus status = HttpStatus.resolve(value);
            if (status != null && status.isError()) {
                return status;
            }
        }
        return code == null ? HttpStatus.NOT_FOUND : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
