package io.reliablepush.shared.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions raised inside controllers to RFC 9457 {@code application/problem+json} (spec 4.3). Extends
 * {@link ResponseEntityExceptionHandler}, so every standard Spring MVC failure (unknown route, unsupported media
 * type, malformed JSON, ...) is a problem document too; validation failures are overridden to 422. Failures in
 * servlet filters are written by {@link ProblemWriter} instead. Rejected values are never echoed back.
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

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
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return handleExceptionInternal(ex, body, new HttpHeaders(), status, request);
    }

    private static Map<String, String> describe(ConstraintViolation<?> violation) {
        return Map.of("field", violation.getPropertyPath().toString(), "message", violation.getMessage());
    }

    private static String message(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "invalid";
    }
}
