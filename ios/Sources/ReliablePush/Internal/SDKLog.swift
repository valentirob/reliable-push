import Foundation
import os

/// The SDK logger: `os.Logger(subsystem: "io.reliablepush", category: "ReliablePush")`, message
/// `event=<name> key=value…`. WARN is written at `.error`, INFO at `.info`; debug and info only when `debug` is true
/// (default: `DEBUG` builds). Fields whose key names a token, a user id or notification content are never written.
/// Callers still must not pass such values under other names. Values are logged `privacy: .private` unless the key is
/// `trackingId` or `deviceId` (the only public fields).
enum SDKLog {
    enum Level: Sendable { case debug, info, warn }

    /// Test hook: receives every formatted message that passes the level filter.
    typealias Sink = @Sendable (Level, String) -> Void

    private static let deniedKeys: Set<String> = [
        "token", "idtoken", "id_token", "devicetoken", "device_token", "authorization",
        "userid", "user_id", "user", "title", "body",
    ]
    private static let publicKeys: Set<String> = ["trackingid", "deviceid"]

    private struct State: Sendable {
        #if DEBUG
        var debug = true
        #else
        var debug = false
        #endif
        var sink: Sink?
    }

    private static let logger = Logger(subsystem: "io.reliablepush", category: "ReliablePush")
    private static let state = Locked(State())

    static var debug: Bool {
        get { state.withLock { $0.debug } }
        set { state.withLock { $0.debug = newValue } }
    }

    static var sink: Sink? {
        get { state.withLock { $0.sink } }
        set { state.withLock { $0.sink = newValue } }
    }

    static func debug(_ event: String, _ fields: [String: String] = [:]) { emit(.debug, event, fields) }
    static func info(_ event: String, _ fields: [String: String] = [:]) { emit(.info, event, fields) }
    static func warn(_ event: String, _ fields: [String: String] = [:]) { emit(.warn, event, fields) }

    private static func emit(_ level: Level, _ event: String, _ fields: [String: String]) {
        let snapshot = state.withLock { $0 }
        if level != .warn && !snapshot.debug { return }
        let kept = fields.filter { !deniedKeys.contains($0.key.lowercased()) }.sorted { $0.key < $1.key }
        let publicPart = kept.filter { publicKeys.contains($0.key.lowercased()) }.map { "\($0.key)=\($0.value)" }
        let privatePart = kept.filter { !publicKeys.contains($0.key.lowercased()) }.map { "\($0.key)=\($0.value)" }
        let head = "event=\(event)"
        let pub = ([head] + publicPart).joined(separator: " ")
        let all = ([head] + kept.map { "\($0.key)=\($0.value)" }).joined(separator: " ")
        snapshot.sink?(level, all)
        let priv = privatePart.joined(separator: " ")
        switch level {
        case .debug: logger.debug("\(pub, privacy: .public) \(priv, privacy: .private)")
        case .info: logger.info("\(pub, privacy: .public) \(priv, privacy: .private)")
        case .warn: logger.error("\(pub, privacy: .public) \(priv, privacy: .private)")
        }
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
