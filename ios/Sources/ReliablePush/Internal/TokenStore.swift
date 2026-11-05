import Foundation

enum TokenLoad: Sendable {
    case registered(deviceId: String, token: DeviceToken)
    case notRegistered
    /// The secret cannot be read now (locked, or an unexpected Keychain error). Nothing is cleared.
    case unavailable
}

/// The registration: token in the Keychain, device id and expiry in `SharedState`.
final class TokenStore: Sendable {
    private let secrets: any SecretStore
    private let state: SharedState
    private let appId: String

    init(secrets: any SecretStore, state: SharedState, appId: String) {
        self.secrets = secrets
        self.state = state
        self.appId = appId
    }

    /// Stores the registration. False when the token is not a valid device token or the Keychain refuses it.
    func save(_ r: Registration) -> Bool {
        guard DeviceToken.parse(r.deviceToken) != nil,
              secrets.write(Data(r.deviceToken.utf8), account: appId) else { return false }
        state.deviceId = r.deviceId
        state.expiresAt = r.expiresAt
        return true
    }

    func load() -> TokenLoad {
        switch secrets.read(account: appId) {
        case .unavailable, .failed:
            return .unavailable
        case .missing:
            // E.g. a restored backup: defaults came back, the ThisDeviceOnly item did not.
            state.clearRegistration()
            return .notRegistered
        case .found(let data):
            guard let raw = String(data: data, encoding: .utf8), let token = DeviceToken.parse(raw),
                  let deviceId = state.deviceId else {
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
