import Foundation

enum TokenLoad: Sendable {
    case registered(deviceId: String, token: DeviceToken)
    case notRegistered
    /// The secret cannot be read now (locked, or an unexpected Keychain error). Nothing is cleared.
    case unavailable
}

/// The registration. The Keychain item holds the whole record (device id + token) so one SecItem write is atomic
/// across the app and the NSE; `SharedState` deviceId/expiresAt are only a cache for non-secret readers.
private struct StoredRegistration: Codable {
    let deviceId: String
    let token: String
}

/// See `StoredRegistration`.
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
              let data = try? JSONEncoder().encode(StoredRegistration(deviceId: r.deviceId, token: r.deviceToken)),
              secrets.write(data, account: appId) else { return false }
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
            guard let record = try? JSONDecoder().decode(StoredRegistration.self, from: data),
                  let token = DeviceToken.parse(record.token) else {
                SDKLog.warn("token.unreadable")
                clear()
                return .notRegistered
            }
            return .registered(deviceId: record.deviceId, token: token)
        }
    }

    func clear() {
        secrets.delete(account: appId)
        state.clearRegistration()
    }
}
