package io.reliablepush.payload;

/**
 * Thrown when a payload cannot be tracked. Nothing is silently dropped or altered: the sender should catch this
 * exception, send the push <em>untracked</em> (its own payload, unchanged) and either skip the registration or cancel it
 * with {@code DELETE /v1/notifications/{trackingId}}. A tracked-but-unsendable push must never become a fallback.
 *
 * <p>{@link #code()} identifies the rule that failed; {@link #getMessage()} is for humans and logs and may change.
 */
public final class PayloadException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The rule that failed. */
    public enum Code {
        /** The tracking id is not a UUID. */
        INVALID_TRACKING_ID,
        /** The sender's data or custom keys contain {@code rp_tid} (any platform) or {@code title}/{@code body} (FCM data). */
        RESERVED_KEY,
        /** {@code ApnsPayloads}: there is no visible {@code aps.alert} (for example a {@code content-available}-only payload). */
        APNS_NO_ALERT,
        /** The APNs payload is above 4,096 bytes of UTF-8. */
        APNS_TOO_LARGE,
        /** An FCM data value (or a notification title/body being moved into data) is not a string. */
        FCM_NON_STRING_DATA,
        /** {@code FcmPayloads.decorate}: a {@code notification} object holds a field other than {@code title} and {@code body}. */
        FCM_UNSUPPORTED_NOTIFICATION_FIELD,
        /** The FCM {@code data} map is above 4,096 bytes of UTF-8 (see {@link FcmPayloads}). */
        FCM_TOO_LARGE,
        /** {@code decorate}: the input is not a JSON object of the expected shape. */
        INVALID_JSON
    }

    private final Code code;

    /** Creates the exception; library code only, senders just catch it. */
    public PayloadException(Code code, String message) {
        super(message);
        this.code = code;
    }

    /** The rule that failed. */
    public Code code() {
        return code;
    }
}
