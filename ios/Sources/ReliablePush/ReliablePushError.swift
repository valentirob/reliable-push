/// Errors thrown by the SDK's public API.
public enum ReliablePushError: Error, Sendable {
    /// The base URL uses cleartext `http` for a host other than `localhost`, `127.0.0.1` or `::1`.
    case insecureBaseURL
    /// The base URL is not an absolute http(s) URL, or carries user info, a query or a fragment.
    case invalidBaseURL
    /// The app id is empty or only whitespace.
    case invalidAppId
    /// The App Group is not usable by this process: `UserDefaults(suiteName:)` or
    /// `FileManager.containerURL(forSecurityApplicationGroupIdentifier:)` returned nil. Check that the App Group id is
    /// spelled the same in `configure`, `ReliablePushExtension` and the App Groups entitlement of both the app and the
    /// Notification Service Extension.
    case appGroupUnavailable
}
