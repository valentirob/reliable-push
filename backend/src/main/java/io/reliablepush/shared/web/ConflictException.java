package io.reliablepush.shared.web;

/** The request conflicts with the current state of the resource: mapped to 409 by {@link ProblemHandler}. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
