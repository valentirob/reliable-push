package io.reliablepush.shared.web;

/**
 * A request parameter or field is invalid: mapped to 422 by {@link ProblemHandler}, with {@code field} in the problem's
 * {@code errors}. The reason must never contain the rejected value.
 */
public class InvalidRequestException extends RuntimeException {

    private final String field;
    private final String reason;

    public InvalidRequestException(String field, String reason) {
        super(field + " " + reason);
        this.field = field;
        this.reason = reason;
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}
