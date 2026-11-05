import Foundation
import Security

enum SecretRead: Sendable, Equatable {
    case found(Data)
    case missing
    /// Keychain not readable right now (device locked before first unlock). Never means "gone".
    case unavailable
    case failed(OSStatus)
}

protocol SecretStore: Sendable {
    func read(account: String) -> SecretRead
    func write(_ data: Data, account: String) -> Bool
    func delete(account: String)
}

/// Generic-password items, `AfterFirstUnlockThisDeviceOnly`, optionally in a shared access group (app ↔ NSE).
final class KeychainStore: SecretStore {
    private let service: String
    private let accessGroup: String?

    init(service: String = "io.reliablepush.devicetoken", accessGroup: String?) {
        self.service = service
        self.accessGroup = accessGroup
    }

    private func query(_ account: String) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let accessGroup { q[kSecAttrAccessGroup as String] = accessGroup }
        return q
    }

    func read(account: String) -> SecretRead {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        switch status {
        case errSecSuccess: return (out as? Data).map { .found($0) } ?? .failed(errSecDecode)
        case errSecItemNotFound: return .missing
        case errSecInteractionNotAllowed: return .unavailable
        default: return .failed(status)
        }
    }

    func write(_ data: Data, account: String) -> Bool {
        let update = SecItemUpdate(query(account) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return true }
        guard update == errSecItemNotFound else { return false }
        var add = query(account)
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    func delete(account: String) {
        SecItemDelete(query(account) as CFDictionary)
    }
}
