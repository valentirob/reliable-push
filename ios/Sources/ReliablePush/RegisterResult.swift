/// Outcome of `ReliablePush.register(idToken:)`.
public enum RegisterResult: Sendable, Equatable {
    /// The device is registered; `deviceId` is the backend's id for this installation.
    case success(deviceId: String)
    /// Registration failed. `httpStatus` is the backend's status code (e.g. 401 for a rejected ID token, 429 when
    /// rate-limited), or `nil` for a network error, a token that could not be stored, or `configure` not called yet.
    case failed(httpStatus: Int?)
}
