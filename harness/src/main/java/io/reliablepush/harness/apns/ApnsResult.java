package io.reliablepush.harness.apns;

/**
 * What APNs answered for one push: {@code SENT}, {@code UNREGISTERED} (the device token is no longer valid) or
 * {@code FAILED}. {@code reason} is Apple's reason code (such as {@code TooManyRequests}), never a token.
 */
public record ApnsResult(Outcome outcome, int status, String reason) {

    public enum Outcome { SENT, UNREGISTERED, FAILED }

    public static ApnsResult sent() {
        return new ApnsResult(Outcome.SENT, 200, null);
    }

    public static ApnsResult unregistered(int status) {
        return new ApnsResult(Outcome.UNREGISTERED, status, null);
    }

    /** {@code status} 0: no HTTP answer (unreachable, timeout, bad token format). */
    public static ApnsResult failed(int status, String reason) {
        return new ApnsResult(Outcome.FAILED, status, reason);
    }
}
