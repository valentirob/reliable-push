package io.reliablepush.shared.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions raised inside controllers to RFC 9457 {@code application/problem+json} (spec 4.3). Extends
 * {@link ResponseEntityExceptionHandler}, so every standard Spring MVC failure (unknown route, unsupported media
 * type, malformed JSON, ...) is a problem document too; validation failures are overridden to 422. Failures in
 * servlet filters are written by {@link ProblemWriter} instead. Rejected values are never echoed back.
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LogManager.getLogger(ProblemHandler.class);

    /**
     * Last resort for exceptions no other handler claims: ERROR with stack trace (spec 9.1; never the request body)
     * and a generic 500 problem that does not leak the exception message. Exceptions with an explicit
     * {@code @ResponseStatus} keep their own handling.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) throws Exception {
        if (AnnotatedElementUtils.hasAnnotation(ex.getClass(), ResponseStatus.class)) {
            throw ex;
        }
        LOGGER.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", ProblemErrorController.UNEXPECTED_DETAIL, ex, request);
    }

    /** The API is JSON-only: multipart handling is disabled, a controller asking for parts gets a client error. */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Object> multipart(MultipartException ex, WebRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", "multipart requests are not supported.", ex, request);
    }

    /** Tomcat refused to parse request parameters (e.g. a form body above max-http-form-post-size): a client error. */
    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<Object> invalidParameters(InvalidParameterException ex, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "The request parameters could not be parsed.", ex, request);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> notFound(NotFoundException ex, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), ex, request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> conflict(ConflictException ex, WebRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), ex, request);
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Object> forbidden(ForbiddenException ex, WebRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), ex, request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Object> unauthorized(UnauthorizedException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (ex.challenge() != null) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, ex.challenge());
        }
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage(), headers, ex, request);
    }

    @ExceptionHandler(RetryLaterException.class)
    ResponseEntity<Object> retryLater(RetryLaterException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        String title = ex.status() == HttpStatus.TOO_MANY_REQUESTS ? "Too Many Requests" : "Service Unavailable";
        return problem(ex.status(), title, ex.getMessage(), headers, ex, request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> invalidRequest(InvalidRequestException ex, WebRequest request) {
        return validationProblem(List.of(Map.of("field", ex.field(), "message", ex.reason())), ex, request);
    }

    @ExceptionHandler(RequestBodyTooLargeException.class)
    ResponseEntity<Object> tooLarge(RequestBodyTooLargeException ex, WebRequest request) {
        return payloadTooLarge(ex, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(ProblemHandler::describe)
                .toList();
        return validationProblem(errors, ex, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError field
                        ? Map.of("field", field.getField(), "message", message(error))
                        : Map.of("message", message(error)))
                .toList();
        return validationProblem(errors, ex, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(error -> Map.of("message", message(error)))
                .toList();
        return validationProblem(errors, ex, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (RequestBodyTooLargeException.in(ex)) {
            return payloadTooLarge(ex, request);
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    private ResponseEntity<Object> payloadTooLarge(Exception ex, WebRequest request) {
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Payload Too Large",
                "The request body must not exceed " + BodySizeLimitFilter.MAX_BODY_BYTES + " bytes.",
                ex,
                request);
    }

    private ResponseEntity<Object> validationProblem(List<Map<String, String>> errors, Exception ex, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "The request is not valid.");
        body.setTitle("Validation Failed");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    private ResponseEntity<Object> problem(HttpStatus status, String title, String detail, Exception ex, WebRequest request) {
        return problem(status, title, detail, new HttpHeaders(), ex, request);
    }

    private ResponseEntity<Object> problem(
            HttpStatus status, String title, String detail, HttpHeaders headers, Exception ex, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static Map<String, String> describe(ConstraintViolation<?> violation) {
        return Map.of("field", violation.getPropertyPath().toString(), "message", violation.getMessage());
    }

    private static String message(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "invalid";
    }
}
