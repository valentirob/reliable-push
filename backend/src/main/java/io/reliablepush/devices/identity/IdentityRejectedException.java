package io.reliablepush.devices.identity;

/**
 * A device-registration credential did not prove an identity. The message is exactly the reason code: never the token,
 * a claim value or key material, so it is safe to log and to map to a problem response.
 */
public class IdentityRejectedException extends RuntimeException {

    public static final String MALFORMED = "malformed";
    public static final String BAD_SIGNATURE = "bad_signature";
    public static final String EXPIRED = "expired";
    public static final String NOT_YET_VALID = "not_yet_valid";
    public static final String WRONG_ISSUER = "wrong_issuer";
    public static final String WRONG_AUDIENCE = "wrong_audience";
    public static final String MISSING_USER_CLAIM = "missing_user_claim";
    public static final String LIFETIME_TOO_LONG = "lifetime_too_long";
    public static final String UNKNOWN_KID = "unknown_kid";
    public static final String JWKS_UNAVAILABLE = "jwks_unavailable";

    private final String reason;

    public IdentityRejectedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /**
     * One of {@code malformed|bad_signature|expired|not_yet_valid|wrong_issuer|wrong_audience|missing_user_claim|
     * lifetime_too_long|unknown_kid|jwks_unavailable}.
     */
    public String reason() {
        return reason;
    }
}
