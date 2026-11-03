package io.reliablepush.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes RFC 9457 problem documents from servlet filters, which {@link ProblemHandler} never sees. It serialises a
 * {@link ProblemDetail} with the application's {@link JsonMapper}, so the JSON is the same as the one the
 * controller advice produces.
 */
@Component
public class ProblemWriter {

    private final JsonMapper mapper;

    public ProblemWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** Writes {@code status} with {@code title}/{@code detail}; {@code instance} is the request path. */
    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        write(request, response, status, title, detail, Map.of());
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail,
            Map<String, String> headers)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(instanceOf(request)));
        response.resetBuffer(); // keeps headers set earlier in the chain, e.g. X-Request-Id
        response.setStatus(status.value());
        headers.forEach(response::setHeader);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(mapper.writeValueAsBytes(problem));
    }

    /** The path (no query), percent-encoded so that it is always a valid URI reference. */
    private static String instanceOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        try {
            return URI.create(uri).getRawPath();
        } catch (IllegalArgumentException e) {
            return "/";
        }
    }
}
