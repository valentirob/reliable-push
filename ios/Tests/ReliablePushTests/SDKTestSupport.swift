import XCTest
import UserNotifications
@testable import ReliablePush

/// Notification settings the tests can change; `current()` answers at once.
final class FakeSettings: NotificationSettingsSource, @unchecked Sendable {
    private let value = Locked(GateSettings.shown)
    func set(_ s: GateSettings) { value.withLock { $0 = s } }
    func current() async -> GateSettings { value.withLock { $0 } }
}

/// Notification settings that never answer (a hanging `notificationSettings()` read).
struct HangingSettings: NotificationSettingsSource {
    func current() async -> GateSettings {
        while !Task.isCancelled { try? await Task.sleep(nanoseconds: 1_000_000_000) }
        return .shown
    }
}

/// Secret store whose every call fails as an unexpected Keychain error would (e.g. -34018, missing entitlement).
final class FailingSecretStore: SecretStore {
    func read(account: String) -> SecretRead { .failed(-34018) }
    func write(_ data: Data, account: String) -> Bool { false }
    func delete(account: String) {}
}

extension GateSettings {
    static let shown = GateSettings(authorized: true, alert: true, notificationCenter: true, lockScreen: true)
}

/// Expiring-activity seam: runs each block with `false` on a background queue (like ProcessInfo), and `expire()`
/// invokes every started block again with `true`. `deny` mimics a refused assertion (only `true`, at once).
final class FakeActivity: ExpiringActivityRunner, @unchecked Sendable {
    private let lock = NSLock()
    private var blocks: [@Sendable (Bool) -> Void] = []
    private var _performed = 0
    private var _returned = 0
    var deny = false

    var performed: Int { lock.lock(); defer { lock.unlock() }; return _performed }
    var returned: Int { lock.lock(); defer { lock.unlock() }; return _returned }

    func perform(reason: String, using block: @escaping @Sendable (Bool) -> Void) {
        lock.lock()
        _performed += 1
        let denied = deny
        if !denied { blocks.append(block) }
        lock.unlock()
        if denied {
            block(true)
            return
        }
        DispatchQueue.global().async {
            block(false)
            self.lock.lock(); self._returned += 1; self.lock.unlock()
        }
    }

    func expire() {
        lock.lock(); let all = blocks; lock.unlock()
        for b in all { b(true) }
    }
}

/// One isolated SDK world: temp queue directory, throwaway defaults suite, in-memory secrets, fake settings/activity.
final class SDKFixture {
    static let now = Date(timeIntervalSince1970: 1_700_000_000)
    static let baseURL = URL(string: "http://localhost:8080")!
    /// Healthy token: 97 % of its lifetime left at `now`.
    static let goodToken = DeviceTokenTests.token(iat: 1_700_000_000 - 100, exp: 1_700_000_000 + 3500)
    /// Expiring token: under 20 % left at `now`, so the next flush refreshes first.
    static let staleToken = DeviceTokenTests.token(iat: 1_700_000_000 - 3500, exp: 1_700_000_000 + 100)
    static let freshToken = DeviceTokenTests.token(iat: 1_700_000_000, exp: 1_700_000_000 + 3600)
    /// Expired 100 s before `now` (inside the backend's events grace period).
    static let expiredToken = DeviceTokenTests.token(iat: 1_700_000_000 - 3700, exp: 1_700_000_000 - 100)

    let root: URL
    let dir: URL
    let suite: String
    let state: SharedState
    let secrets = FakeSecretStore()
    let settings = FakeSettings()
    let activity = FakeActivity()
    let log = WarnBox()

    init() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("rp-sdk-\(UUID().uuidString)")
        dir = root.appendingPathComponent("Library/Application Support/ReliablePush")
        suite = "rp.test.\(UUID().uuidString)"
        state = SharedState(defaults: UserDefaults(suiteName: suite)!)
        let box = log
        SDKLog.sink = { level, message in box.add("\(level) \(message)") }
    }

    var environment: SDKEnvironment { environment() }

    /// The fixture's environment, with the secret store or the settings source replaced (fault injection).
    func environment(secrets: (any SecretStore)? = nil, settings: (any NotificationSettingsSource)? = nil) -> SDKEnvironment {
        SDKEnvironment(state: state, queueDirectory: dir, secrets: secrets ?? self.secrets, settings: settings ?? self.settings,
                       sessionConfiguration: { StubURLProtocol.configuration() },
                       now: { SDKFixture.now }, activity: activity)
    }

    var queue: EventQueue { EventQueue(directory: dir) }
    var flushLock: FileLock { FileLock(url: dir.appendingPathComponent("flush.lock")) }

    func tokens(_ appId: String = "app1") -> TokenStore { TokenStore(secrets: secrets, state: state, appId: appId) }

    func storeConfig(appId: String = "app1") { state.config = (Self.baseURL, appId) }

    func register(appId: String = "app1", deviceId: String = "dev1", token: String = goodToken) {
        XCTAssertTrue(tokens(appId).save(Registration(deviceId: deviceId, deviceToken: token,
                                                      expiresAt: Self.now.addingTimeInterval(3500))))
    }

    func cleanup() {
        SDKLog.sink = nil
        UserDefaults().removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: root)
    }

    func warned(_ event: String) -> Bool { log.messages.contains { $0.hasPrefix("warn") && $0.contains("event=\(event)") } }

    /// (trackingId, type) of every event sent to `/v1/events`, in order.
    static func sentEvents() -> [String] {
        StubURLProtocol.requests.filter { $0.url.path == "/v1/events" }.flatMap { r -> [String] in
            let obj = try? JSONSerialization.jsonObject(with: r.body) as? [String: Any]
            return ((obj?["events"] as? [[String: Any]]) ?? []).map { "\($0["trackingId"] ?? "")|\($0["type"] ?? "")" }
        }
    }

    /// Stub reply acknowledging every event of the request as ACCEPTED.
    static func acceptAll(_ req: URLRequest) -> StubURLProtocol.Reply {
        var body = req.httpBody ?? Data()
        if let s = req.httpBodyStream {
            s.open(); defer { s.close() }
            var buf = [UInt8](repeating: 0, count: 4096)
            while s.hasBytesAvailable { let n = s.read(&buf, maxLength: buf.count); if n <= 0 { break }; body.append(buf, count: n) }
        }
        let obj = try? JSONSerialization.jsonObject(with: body) as? [String: Any]
        let ids = ((obj?["events"] as? [[String: Any]]) ?? []).compactMap { $0["trackingId"] as? String }
        let list = ids.map { #"{"trackingId":"\#($0)","outcome":"ACCEPTED"}"# }.joined(separator: ",")
        return .response(status: 200, body: Data(#"{"items":[\#(list)]}"#.utf8))
    }

    static func refreshed(_ token: String = freshToken) -> StubURLProtocol.Reply {
        .response(status: 200, body: Data(#"{"deviceToken":"\#(token)","expiresAt":"2023-11-15T00:00:00Z"}"#.utf8))
    }

    static func registration(deviceId: String = "dev1", token: String = goodToken) -> StubURLProtocol.Reply {
        .response(status: 201, body: Data(#"{"deviceId":"\#(deviceId)","deviceToken":"\#(token)","expiresAt":"2023-11-15T00:00:00Z"}"#.utf8))
    }
}

/// Polls `condition` (on the calling thread, spinning the run loop) until true or `timeout`.
func waitUntil(timeout: TimeInterval = 5, _ condition: () -> Bool) -> Bool {
    let end = Date().addingTimeInterval(timeout)
    while Date() < end {
        if condition() { return true }
        RunLoop.current.run(until: Date().addingTimeInterval(0.02))
    }
    return condition()
}

/// Async variant of `waitUntil`.
func waitUntilAsync(timeout: TimeInterval = 5, _ condition: () -> Bool) async -> Bool {
    let end = Date().addingTimeInterval(timeout)
    while Date() < end {
        if condition() { return true }
        try? await Task.sleep(nanoseconds: 20_000_000)
    }
    return condition()
}
