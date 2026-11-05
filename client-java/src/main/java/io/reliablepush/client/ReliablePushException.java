package io.reliablepush.client;

import java.time.Duration;
import java.util.Optional;

/**
 * Failure of a sender API call. {@link #status()} is the HTTP status, or {@code -1} for I/O errors and timeouts.
 * Messages never contain the API key.
 */
public class ReliablePushException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient ProblemDetails problem;
    private final transient Duration retryAfter;

    public ReliablePushException(String message, int status, ProblemDetails problem, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.problem = problem;
        this.retryAfter = retryAfter;
    }

    /** HTTP status, or -1 when no response was received (I/O error, timeout, interruption). */
    public int status() {
        return status;
    }

    /** The parsed problem+json body, when the server sent one. */
    public Optional<ProblemDetails> problem() {
        return Optional.ofNullable(problem);
    }

    /** The server's {@code Retry-After} (429/503), when given in seconds. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
