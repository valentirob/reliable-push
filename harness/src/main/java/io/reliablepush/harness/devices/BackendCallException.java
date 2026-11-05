package io.reliablepush.harness.devices;

/**
 * A device API call the simulator made for a console or scenario request was answered with an error (or not at all).
 * Carries the backend's HTTP status ({@code 0} = timeout or I/O failure) and its {@code Retry-After} seconds when
 * given; the message never contains tokens, grants or bodies.
 */
public class BackendCallException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final Integer retryAfterSeconds;

    public BackendCallException(String what, int status, Integer retryAfterSeconds) {
        super(what + ": HTTP status " + status);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** The backend's HTTP status; {@code 0} when it did not answer (timeout or I/O failure). */
    public int status() {
        return status;
    }

    /** The backend's {@code Retry-After} in seconds, or {@code null}. */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
