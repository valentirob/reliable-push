package io.reliablepush.harness.receiver;

/** How the fallback receiver answers a (correctly signed) webhook. */
public enum ReceiverMode {
    /** 200. */
    OK,
    /** 500: a retryable failure. */
    FAIL_500,
    /** 400: a permanent failure (no retry). */
    FAIL_400,
    /** Sleep for the configured hang time (bounded), then 200. */
    HANG
}
