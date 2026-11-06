package io.reliablepush.shared.web;

/** The caller is authenticated but not allowed to touch the resource: mapped to 403 by {@link ProblemHandler}. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
