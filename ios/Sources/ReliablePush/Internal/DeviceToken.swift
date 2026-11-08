import Foundation

/// A device token (ES256 JWT). Only `iat`/`exp` are read; there is no signature check on the device.
struct DeviceToken: Sendable, Equatable {
    let raw: String
    let issuedAt: Date
    let expiresAt: Date

    /// True at or after `exp`.
    func isExpired(now: Date) -> Bool { now >= expiresAt }

    /// True when expired or the remaining lifetime is below 20 % of the total lifetime.
    func needsRefresh(now: Date) -> Bool {
        let remaining = expiresAt.timeIntervalSince(now)
        return remaining <= 0 || remaining * 5 < expiresAt.timeIntervalSince(issuedAt)
    }

    private static let maxSeconds: Int64 = 100_000_000_000

    static func parse(_ raw: String) -> DeviceToken? {
        let parts = raw.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        var b64 = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        b64 += String(repeating: "=", count: (4 - b64.count % 4) % 4)
        guard let data = Data(base64Encoded: b64),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let iat = integer(obj["iat"]), let exp = integer(obj["exp"]),
              iat >= 0, exp > iat, iat < maxSeconds, exp < maxSeconds
        else { return nil }
        return DeviceToken(raw: raw, issuedAt: Date(timeIntervalSince1970: TimeInterval(iat)),
                           expiresAt: Date(timeIntervalSince1970: TimeInterval(exp)))
    }

    private static func integer(_ v: Any?) -> Int64? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let d = n.doubleValue
        guard d.isFinite, d == d.rounded(), abs(d) < 1e15 else { return nil }
        return n.int64Value
    }
}
