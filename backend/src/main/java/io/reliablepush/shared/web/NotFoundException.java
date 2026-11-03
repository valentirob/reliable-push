package io.reliablepush.shared.web;

/** The addressed resource does not exist (or is not visible to the caller): mapped to 404 by {@link ProblemHandler}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
