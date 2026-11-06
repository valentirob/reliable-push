package io.reliablepush.shared.web;

import java.io.IOException;

/**
 * Thrown by the body-limiting input stream once a request body exceeds {@link BodySizeLimitFilter#MAX_BODY_BYTES}.
 * An {@link IOException} so that readers of the stream see it as a read failure; message converters usually wrap it,
 * hence {@link #in(Throwable)}.
 */
public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException() {
        super("Request body exceeds " + BodySizeLimitFilter.MAX_BODY_BYTES + " bytes");
    }

    /** True when {@code t} or one of its causes is a {@link RequestBodyTooLargeException}. */
    public static boolean in(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof RequestBodyTooLargeException) {
                return true;
            }
        }
        return false;
    }
}
