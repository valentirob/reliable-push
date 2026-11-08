/// Outcome of `ReliablePush.register(idToken:)` and `ReliablePush.ensureRegistered(idTokenProvider:)`.
public enum RegisterResult: Sendable, Equatable {
    /// The device is registered (or its token was renewed); `deviceId` is the backend's id for this installation.
    case success(deviceId: String)
    /// Registration failed. `httpStatus` is the backend's status code (e.g. 401 for a rejected ID token, 429 when
    /// rate-limited), or `nil` for a network error, a token that could not be stored, a Keychain that cannot be read
    /// now, a logout during the call, or `configure` not called yet.
    case failed(httpStatus: Int?)
    /// `ensureRegistered` only: a registration was needed and the ID-token provider returned `nil`. Nothing was sent.
    case notLoggedIn
    /// `ensureRegistered` only: the stored device token is valid and needs no renewal. Nothing was sent.
    case alreadyRegistered
}
