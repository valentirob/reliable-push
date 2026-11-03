package io.reliablepush.devices;

/**
 * A device token failed verification. The message is exactly the reason code (never the token, a claim or key
 * material), so it is safe to log and to map to a problem response.
 */
public class InvalidDeviceTokenException extends RuntimeException {

    public static final String MALFORMED = "malformed";
    public static final String BAD_SIGNATURE = "bad_signature";
    public static final String EXPIRED = "expired";
    public static final String UNKNOWN_KID = "unknown_kid";
    public static final String WRONG_ISSUER = "wrong_issuer";

    private final String reason;

    public InvalidDeviceTokenException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /** One of {@code malformed|bad_signature|expired|unknown_kid|wrong_issuer}. */
    public String reason() {
        return reason;
    }
}
