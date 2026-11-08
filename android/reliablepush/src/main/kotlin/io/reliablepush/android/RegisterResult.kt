package io.reliablepush.android

/** Outcome of [ReliablePush.register] and [ReliablePush.ensureRegistered], delivered on the main thread. */
public sealed interface RegisterResult {
    /** The device is registered (or its device token was renewed); [deviceId] is the backend's id for this installation. */
    public data class Success(val deviceId: String) : RegisterResult

    /**
     * Registration failed. [httpStatus] is the backend's status (e.g. `401` for a rejected ID token, `429` rate
     * limited, `503` identity provider unavailable), or `null` for a network error, a token that could not be stored,
     * or an SDK that is not configured.
     */
    public data class Failed(val httpStatus: Int?) : RegisterResult

    /**
     * [ReliablePush.ensureRegistered] needed an ID token and the provider had none (it delivered `null`, threw, or did
     * not deliver within 30 s). Nothing was sent.
     */
    public data object NotLoggedIn : RegisterResult

    /** [ReliablePush.ensureRegistered] found a device token with enough validity left: nothing was sent. */
    public data object AlreadyRegistered : RegisterResult
}
