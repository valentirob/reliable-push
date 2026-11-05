package io.reliablepush.harness.fcm;

/** What FCM answered for one message: {@code SENT}, {@code UNREGISTERED} (token no longer valid) or {@code FAILED}. */
public record FcmResult(Outcome outcome, int status) {

    public enum Outcome { SENT, UNREGISTERED, FAILED }

    public static FcmResult sent() {
        return new FcmResult(Outcome.SENT, 200);
    }

    public static FcmResult unregistered() {
        return new FcmResult(Outcome.UNREGISTERED, 404);
    }

    /** {@code status} 0: no HTTP answer (unreachable, timeout, no access token). */
    public static FcmResult failed(int status) {
        return new FcmResult(Outcome.FAILED, status);
    }
}
