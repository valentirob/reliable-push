package io.reliablepush.android

/** Outcome of [ReliablePush.register], delivered on the main thread. */
public sealed interface RegisterResult {
    /** The device is registered; [deviceId] is the backend's id for this installation. */
    public data class Success(val deviceId: String) : RegisterResult

    /**
     * Registration failed. [httpStatus] is the backend's status (e.g. `401` for a rejected ID token, `429` rate
     * limited, `503` identity provider unavailable), or `null` for a network error or a token that could not be stored.
     */
    public data class Failed(val httpStatus: Int?) : RegisterResult
}
