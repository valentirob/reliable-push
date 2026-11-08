import Foundation

/// Non-secret state shared between the app and the NSE (App Group `UserDefaults`). Never holds the token.
final class SharedState: Sendable {
    private enum Key {
        static let baseURL = "io.reliablepush.baseURL"
        static let appId = "io.reliablepush.appId"
        static let deviceId = "io.reliablepush.deviceId"
        static let expiresAt = "io.reliablepush.expiresAt"
        static let retryNotBefore = "io.reliablepush.retryNotBefore"
        static let renewalNeeded = "io.reliablepush.renewalNeeded"
    }

    private nonisolated(unsafe) let defaults: UserDefaults // UserDefaults is thread-safe

    init(defaults: UserDefaults) { self.defaults = defaults }

    var config: (baseURL: URL, appId: String)? {
        get {
            guard let s = defaults.string(forKey: Key.baseURL), let url = URL(string: s),
                  let appId = defaults.string(forKey: Key.appId) else { return nil }
            return (url, appId)
        }
        set {
            if let newValue {
                defaults.set(newValue.baseURL.absoluteString, forKey: Key.baseURL)
                defaults.set(newValue.appId, forKey: Key.appId)
            } else {
                defaults.removeObject(forKey: Key.baseURL)
                defaults.removeObject(forKey: Key.appId)
            }
        }
    }

    var deviceId: String? {
        get { defaults.string(forKey: Key.deviceId) }
        set { set(newValue, forKey: Key.deviceId) }
    }

    var expiresAt: Date? {
        get { date(Key.expiresAt) }
        set { setDate(newValue, Key.expiresAt) }
    }

    /// Set after a 429 (`Retry-After`); the sender does not flush before this instant.
    var retryNotBefore: Date? {
        get { date(Key.retryNotBefore) }
        set { setDate(newValue, Key.retryNotBefore) }
    }

    /// Set when `/v1/events` answered 401 (in the app or the extension); `ensureRegistered` then renews the token.
    var renewalNeeded: Bool {
        get { defaults.bool(forKey: Key.renewalNeeded) }
        set { if newValue { defaults.set(true, forKey: Key.renewalNeeded) } else { defaults.removeObject(forKey: Key.renewalNeeded) } }
    }

    /// Removes the registration fields, `renewalNeeded` included (not config, not retry-not-before).
    func clearRegistration() {
        defaults.removeObject(forKey: Key.deviceId)
        defaults.removeObject(forKey: Key.expiresAt)
        defaults.removeObject(forKey: Key.renewalNeeded)
    }

    private func set(_ v: String?, forKey k: String) {
        if let v { defaults.set(v, forKey: k) } else { defaults.removeObject(forKey: k) }
    }
    private func date(_ k: String) -> Date? {
        defaults.object(forKey: k) == nil ? nil : Date(timeIntervalSince1970: defaults.double(forKey: k))
    }
    private func setDate(_ d: Date?, _ k: String) {
        if let d { defaults.set(d.timeIntervalSince1970, forKey: k) } else { defaults.removeObject(forKey: k) }
    }
}
