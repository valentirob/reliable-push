import Foundation
import UserNotifications

/// Entry point of the Reliable Push iOS SDK: reports whether each push was delivered (shown, from the Notification
/// Service Extension via `ReliablePushExtension`) and read (tapped, via `trackOpen(response:)`).
///
/// The app and its Notification Service Extension share state through an App Group (configuration, device id, event
/// queue) and the Keychain (device token). Every method may be called from any thread; completion handlers run on
/// the main actor.
///
/// Data sent to the backend: device id, tracking id, event type (`DELIVERED`/`READ`), timestamp and app id; at
/// registration also the host's ID token. Never notification content.
public enum ReliablePush {
    /// The SDK version, sent as `User-Agent: reliable-push-ios/<version>`.
    public static let version = "0.1.0-SNAPSHOT"

    /// Configures the SDK. Call it in `application(_:didFinishLaunchingWithOptions:)` on every launch, before any
    /// other method.
    ///
    /// The same base URL and app id as the stored configuration is a no-op (the registration is kept). A different
    /// one replaces the stored configuration and clears the registration, the queued events and any rate-limit
    /// back-off. The configuration is persisted in the App Group's `UserDefaults`, where the extension reads it. The
    /// first call in a process starts one flush of the events still queued (bounded, inside an expiring activity).
    ///
    /// - Parameters:
    ///   - baseURL: The backend, `https` (cleartext `http` only for `localhost`, `127.0.0.1`, `::1`).
    ///   - appId: The app id registered at the backend.
    ///   - appGroup: The App Group id shared with the Notification Service Extension (e.g. `group.com.example.app`).
    ///   - options: Keychain access group override; see `ReliablePushOptions`.
    /// - Throws: `ReliablePushError.insecureBaseURL` / `.invalidBaseURL` for an unusable base URL,
    ///   `.invalidAppId` for a blank app id, `.appGroupUnavailable` when the App Group cannot be opened.
    public static func configure(baseURL: String, appId: String, appGroup: String,
                                 options: ReliablePushOptions = .init()) throws(ReliablePushError) {
        try configure(baseURL: baseURL, appId: appId) { () throws(ReliablePushError) in
            try SDKEnvironment.appGroup(appGroup, options: options)
        }
    }

    /// Registers this installation for the signed-in user, exchanging the app's ID token for a device token stored in
    /// the shared Keychain. Call it after login and on app start. A successful registration then flushes the queue.
    ///
    /// - Parameters:
    ///   - idToken: The OIDC ID token (or signed grant) of the signed-in user. Never logged.
    ///   - completion: Called on the main actor with the result; `.failed(httpStatus: nil)` if `configure` has not
    ///     been called.
    public static func register(idToken: String, completion: (@MainActor @Sendable (RegisterResult) -> Void)? = nil) {
        Task {
            let result = await register(idToken: idToken)
            await completion?(result)
        }
    }

    /// Async variant of `register(idToken:completion:)`.
    ///
    /// - Returns: `.success(deviceId:)`, or `.failed(httpStatus:)` (nil status: network error, token not stored, or
    ///   `configure` not called).
    public static func register(idToken: String) async -> RegisterResult {
        guard let sdk = SDK.current else {
            SDKLog.warn("device.register_failed", ["reason": "not_configured"])
            return .failed(httpStatus: nil)
        }
        let result = await sdk.register(idToken: idToken)
        if case .success = result { sdk.startFlush() }
        return result
    }

    /// Reports that the user opened a notification. Call it from
    /// `userNotificationCenter(_:didReceive:withCompletionHandler:)`.
    ///
    /// For any action except `UNNotificationDismissActionIdentifier`, it reads `rp_tid` from
    /// `response.notification.request.content.userInfo`, persists `READ` in the queue before returning, and starts a
    /// flush. When the SDK is not configured or the device is not registered, nothing is persisted (WARN
    /// `tracking.not_registered`).
    ///
    /// - Returns: `true` when the notification is the SDK's (it carries a valid `rp_tid`), whatever the action;
    ///   `false` when it is not (no `rp_tid`, or a malformed one), so the host handles it.
    @discardableResult
    public static func trackOpen(response: UNNotificationResponse) -> Bool {
        trackOpen(userInfo: response.notification.request.content.userInfo, actionIdentifier: response.actionIdentifier)
    }

    /// Logs this installation out: revokes the device token at the backend (best effort), then clears the token, the
    /// registration state and the queued events in any case. The configuration is kept.
    ///
    /// - Parameter completion: Called on the main actor with `true` when the backend confirmed the revoke (or nothing
    ///   was registered), `false` when it could not be reached or refused it, or `configure` has not been called.
    public static func logout(completion: (@MainActor @Sendable (Bool) -> Void)? = nil) {
        Task {
            let confirmed: Bool
            if let sdk = SDK.current {
                confirmed = await sdk.logout()
            } else {
                SDKLog.warn("device.revoked", ["confirmed": "false", "reason": "not_configured"])
                confirmed = false
            }
            await completion?(confirmed)
        }
    }

    /// The backend's id for this installation, or `nil` when not configured or not registered.
    public static var deviceId: String? { SDK.current?.env.state.deviceId }

    // MARK: Internal (also the test entry points)

    static func configure(baseURL: String, appId: String, environment: SDKEnvironment) throws(ReliablePushError) {
        try configure(baseURL: baseURL, appId: appId) { environment }
    }

    /// Validates the arguments first (so a bad URL never touches the App Group), then resolves the environment.
    private static func configure(baseURL: String, appId: String,
                                  environment: () throws(ReliablePushError) -> SDKEnvironment) throws(ReliablePushError) {
        let url = try BaseURL.validate(baseURL)
        if appId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { throw .invalidAppId }
        SDK.configure(baseURL: url, appId: appId, env: try environment())
    }

    static func trackOpen(userInfo: [AnyHashable: Any], actionIdentifier: String) -> Bool {
        guard case let .valid(trackingId) = TrackingId.parse(userInfo) else { return false }
        if actionIdentifier == UNNotificationDismissActionIdentifier { return true }
        guard let sdk = SDK.current else {
            SDKLog.warn("tracking.not_registered", ["trackingId": trackingId, "reason": "not_configured"])
            return true
        }
        if sdk.record(trackingId, .read) { sdk.startFlush() }
        return true
    }
}
