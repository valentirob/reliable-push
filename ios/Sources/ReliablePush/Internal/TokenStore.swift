import Foundation

enum TokenLoad: Sendable {
    case registered(deviceId: String, token: DeviceToken)
    case notRegistered
    /// The secret cannot be read now (locked, or an unexpected Keychain error). Nothing is cleared.
    case unavailable
}

/// The registration. The Keychain item holds the whole record, JSON `{"deviceId", "token"}`, so one SecItem write is
/// atomic across the app and the NSE; `SharedState` deviceId/expiresAt are only a cache for non-secret readers.
final class TokenStore: Sendable {
    private let secrets: any SecretStore
    private let state: SharedState
    private let appId: String

    init(secrets: any SecretStore, state: SharedState, appId: String) {
        self.secrets = secrets
        self.state = state
        self.appId = appId
    }

    /// Stores the registration and clears `renewalNeeded` (a new token needs no renewal). False when the token is not
    /// a valid device token or the Keychain refuses it.
    func save(_ r: Registration) -> Bool {
        guard DeviceToken.parse(r.deviceToken) != nil,
              let data = JSON.data(["deviceId": r.deviceId, "token": r.deviceToken]),
              secrets.write(data, account: appId) else { return false }
        state.deviceId = r.deviceId
        state.expiresAt = r.expiresAt
        state.renewalNeeded = false
        return true
    }

    /// True after `/v1/events` answered 401 with the stored token (App Group state, shared with the extension).
    var renewalNeeded: Bool { state.renewalNeeded }

    func markRenewalNeeded() { state.renewalNeeded = true }

    func load() -> TokenLoad {
        switch secrets.read(account: appId) {
        case .unavailable:
            return .unavailable
        case .failed(let status):
            // not "locked" (that is .unavailable): e.g. -34018, the access group is missing from the entitlements
            SDKLog.warn("token.unavailable", ["status": "\(status)"])
            return .unavailable
        case .missing:
            // E.g. a restored backup: defaults came back, the ThisDeviceOnly item did not.
            state.clearRegistration()
            return .notRegistered
        case .found(let data):
            guard let record = JSON.object(data), let deviceId = record["deviceId"] as? String,
                  let raw = record["token"] as? String, let token = DeviceToken.parse(raw) else {
                SDKLog.warn("token.unreadable")
                clear()
                return .notRegistered
            }
            return .registered(deviceId: deviceId, token: token)
        }
    }

    func clear() {
        secrets.delete(account: appId)
        state.clearRegistration()
    }
}
