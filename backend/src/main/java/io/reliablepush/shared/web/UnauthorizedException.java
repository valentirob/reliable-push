package io.reliablepush.shared.web;

/**
 * The caller could not be authenticated: mapped to 401 by {@link ProblemHandler}, with a {@code WWW-Authenticate}
 * challenge when {@code challenge} is given. The message is shown to the client, so it must be the same whatever
 * the reason and never contain a credential or a claim.
 */
public class UnauthorizedException extends RuntimeException {

    private final String challenge;

    public UnauthorizedException(String message, String challenge) {
        super(message);
        this.challenge = challenge;
    }

    /** The {@code WWW-Authenticate} value, or {@code null}. */
    public String challenge() {
        return challenge;
    }
}
