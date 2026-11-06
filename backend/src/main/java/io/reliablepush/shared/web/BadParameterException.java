package io.reliablepush.shared.web;

/**
 * Like {@link InvalidRequestException} but mapped to 400 (the admin API reports malformed query parameters as
 * {@code 400}, spec 4). The reason must never contain the rejected value.
 */
public class BadParameterException extends InvalidRequestException {

    public BadParameterException(String field, String reason) {
        super(field, reason);
    }
}
