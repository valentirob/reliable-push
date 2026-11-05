import Foundation

/// The payload contract's `rp_tid` (a UUID string at the top level of `userInfo`).
enum TrackingId {
    static let key = "rp_tid"

    enum Parsed {
        /// No `rp_tid`: the notification is not the SDK's.
        case notOurs
        /// `rp_tid` present but not a UUID string (already logged as WARN).
        case malformed
        case valid(String)
    }

    static func parse(_ userInfo: [AnyHashable: Any]) -> Parsed {
        guard let raw = userInfo[key] else { return .notOurs }
        guard let s = raw as? String, s.utf8.count == 36, UUID(uuidString: s) != nil else {
            SDKLog.warn("tracking.event_dropped", ["reason": "malformed_rp_tid"])
            return .malformed
        }
        return .valid(s)
    }
}
