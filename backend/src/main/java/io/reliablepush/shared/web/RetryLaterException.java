package io.reliablepush.shared.web;

import org.springframework.http.HttpStatus;

/**
 * The request cannot be served now but may succeed later: 429 (rate limited) or 503 (a dependency is unavailable),
 * both answered by {@link ProblemHandler} with a {@code Retry-After} header.
 */
public class RetryLaterException extends RuntimeException {

    private final HttpStatus status;
    private final long retryAfterSeconds;

    private RetryLaterException(HttpStatus status, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static RetryLaterException tooManyRequests(long retryAfterSeconds) {
        return new RetryLaterException(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded; retry after " + retryAfterSeconds + " seconds.", retryAfterSeconds);
    }

    public static RetryLaterException serviceUnavailable(String message, long retryAfterSeconds) {
        return new RetryLaterException(HttpStatus.SERVICE_UNAVAILABLE, message, retryAfterSeconds);
    }

    public HttpStatus status() {
        return status;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
