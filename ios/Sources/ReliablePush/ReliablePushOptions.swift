import Foundation

/// Options shared by `ReliablePush.configure` (app) and `ReliablePushExtension` (Notification Service Extension).
///
/// In the app, options are fixed for the process once configured: a later `configure` with the same base URL and
/// app id keeps the options of the first call.
public struct ReliablePushOptions: Sendable {
    /// Keychain access group holding the device token, shared by the app and the extension. `nil` (the default) uses
    /// the App Group id, which is a valid keychain access group and needs no extra entitlement.
    public var keychainAccessGroup: String?
    /// Overall time, in seconds, the extension spends (settings read and the one send of `DELIVERED`) before it calls
    /// the content handler. Default 2; clamped to 0...25 so the notification is always handed back well inside the
    /// system's ~30 s budget.
    public var extensionSendTimeout: TimeInterval

    /// Creates options; every parameter has the documented default.
    public init(keychainAccessGroup: String? = nil, extensionSendTimeout: TimeInterval = 2) {
        self.keychainAccessGroup = keychainAccessGroup
        self.extensionSendTimeout = extensionSendTimeout
    }
}
