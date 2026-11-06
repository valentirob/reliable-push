import Foundation
import os

/// The SDK logger: unified logging, subsystem `io.reliablepush`, category `ReliablePush` (`os_log`, see `log`),
/// message `event=<name> key=value…` (fields in call order). WARN is written at `.error`, INFO at `.info`; debug and
/// info only when `debug` is true (default: `DEBUG` builds).
///
/// Fields whose key names a token, a user id or notification content are never written; callers still must not pass
/// such values under other names. Public (R19): `trackingId`, `deviceId` and the low-cardinality, non-personal
/// `reason`, `status`, `count`, `outcome`, `type`, `call`. Every other field is logged `private`.
enum SDKLog {
    enum Level: Sendable { case debug, info, warn }

    /// Test hook: receives every formatted message that passes the level filter.
    typealias Sink = @Sendable (Level, String) -> Void

    private struct State: Sendable {
        #if DEBUG
        var debug = true
        #else
        var debug = false
        #endif
        var sink: Sink?
    }

    // `os_log` with an `OSLog`, not `os.Logger`: same subsystem, category, levels and privacy in the unified log,
    // but `Logger`'s format strings go into `__oslogstring`, a 16 KB-aligned section that costs a whole page of the
    // size budget (spec §9.2).
    private static let log = OSLog(subsystem: "io.reliablepush", category: "ReliablePush")
    private static let state = Locked(State())

    static var debug: Bool {
        get { state.withLock { $0.debug } }
        set { state.withLock { $0.debug = newValue } }
    }

    static var sink: Sink? {
        get { state.withLock { $0.sink } }
        set { state.withLock { $0.sink = newValue } }
    }

    static func debug(_ event: String, _ fields: KeyValuePairs<String, String> = [:]) { emit(.debug, event, fields) }
    static func info(_ event: String, _ fields: KeyValuePairs<String, String> = [:]) { emit(.info, event, fields) }
    static func warn(_ event: String, _ fields: KeyValuePairs<String, String> = [:]) { emit(.warn, event, fields) }

    /// Fields are written in the order given (`KeyValuePairs`: no dictionary, no sort, so a small binary).
    private static func emit(_ level: Level, _ event: String, _ fields: KeyValuePairs<String, String>) {
        let snapshot = state.withLock { $0 }
        if level != .warn && !snapshot.debug { return }
        let parts = format(event, fields)
        snapshot.sink?(level, parts.all)
        let type: OSLogType = level == .warn ? .error : level == .info ? .info : .debug
        os_log("%{public}@ %{private}@", log: log, type: type, parts.pub as NSString, parts.priv as NSString)
    }

    /// The public part (`event=` and the public fields), the private part, and the whole message (for the sink).
    static func format(_ event: String, _ fields: KeyValuePairs<String, String>) -> (pub: String, priv: String, all: String) {
        var pub = "event=" + event
        var all = pub
        var priv = ""
        for (key, value) in fields {
            let pair = key + "=" + value
            switch key.lowercased() {
            case "token", "idtoken", "id_token", "devicetoken", "device_token", "authorization",
                 "userid", "user_id", "user", "title", "body":
                continue
            case "trackingid", "deviceid", "reason", "status", "count", "outcome", "type", "call":
                pub += " " + pair
            default:
                priv += priv.isEmpty ? pair : " " + pair
            }
            all += " " + pair
        }
        return (pub, priv, all)
    }
}

/// Minimal NSLock-backed box (OSAllocatedUnfairLock needs iOS 16).
final class Locked<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value
    init(_ value: Value) { self.value = value }
    func withLock<R>(_ body: (inout Value) -> R) -> R {
        lock.lock(); defer { lock.unlock() }
        return body(&value)
    }
}
