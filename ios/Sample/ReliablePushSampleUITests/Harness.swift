import Foundation
import XCTest

/// Values the UI tests pass to the sample app through `launchEnvironment`. The defaults are the Debug defaults of the
/// app and the local harness stack of `scripts/ios-e2e.sh`; `xcodebuild` forwards `TEST_RUNNER_<NAME>` variables, so
/// `TEST_RUNNER_RP_HARNESS_URL=…` overrides one.
enum E2EConfig {
    static let baseURL = value("RP_BASE_URL", "http://127.0.0.1:8091")
    static let harnessURL = value("RP_HARNESS_URL", "http://127.0.0.1:8090")
    static let shop = "shop"
    /// Device tokens valid 20 s (the harness's `news` app): `ensureRegistered` refreshes when less than 4 s are left.
    static let news = "news"

    private static func value(_ name: String, _ fallback: String) -> String {
        let v = ProcessInfo.processInfo.environment[name] ?? ""
        return v.isEmpty ? fallback : v
    }
}

/// The harness console API (`/console/api/…`): it plays the customer's backend (sends) and shows the notification
/// state, the fallback webhooks and the backend log to the tests. Every POST carries `Content-Type: application/json`.
struct Harness: Sendable {
    let baseURL: URL

    init(_ url: String = E2EConfig.harnessURL) {
        baseURL = URL(string: url)!
    }

    /// Sends a tracked push to the user (the harness registers it at the backend); returns the tracking id.
    func push(appId: String, userId: String, title: String, body: String, policy: String,
              timeoutMinutes: Int) async throws -> String {
        let json = try await post("/console/api/push", [
            "appId": appId, "userId": userId, "title": title, "body": body, "policy": policy,
            "timeoutMinutes": timeoutMinutes,
        ], expect: 200)
        guard let tid = json["trackingId"] as? String else { throw HarnessError.unexpected("no trackingId in \(json)") }
        return tid
    }

    /// The device id of the user's REAL device (announced by the sample after `register`), or nil.
    func realDeviceId(appId: String, userId: String) async throws -> String? {
        let list = try await getArray("/console/api/devices?appId=\(enc(appId))&userId=\(enc(userId))")
        return list.first { ($0["kind"] as? String) == "REAL" }?["deviceId"] as? String
    }

    /// Whether the harness has APNs credentials (real APNs mode); never carries a token.
    func apnsEnabled() async throws -> Bool {
        (try await getArray("/console/api/apps")).contains { ($0["apnsEnabled"] as? Bool) == true }
    }

    /// Whether the user's REAL device reported its APNs token (the harness shows only this flag).
    func hasApnsToken(appId: String, userId: String) async throws -> Bool {
        let list = try await getArray("/console/api/devices?appId=\(enc(appId))&userId=\(enc(userId))")
        return list.contains { ($0["kind"] as? String) == "REAL" && ($0["hasApnsToken"] as? Bool) == true }
    }

    /// `{notification: {state, …}, timeline: […], webhooks: […]}`
    func notification(_ tid: String) async throws -> [String: Any] {
        try await getObject("/console/api/notifications/\(tid)")
    }

    func state(_ tid: String) async throws -> String? {
        (try await notification(tid)["notification"] as? [String: Any])?["state"] as? String
    }

    func webhookCount(_ tid: String) async throws -> Int {
        (try await notification(tid)["webhooks"] as? [Any])?.count ?? 0
    }

    /// Coarse: any timeline entry whose JSON mentions the quoted state (e.g. a state change to DELIVERED).
    func timelineHas(_ tid: String, _ state: String) async throws -> Bool {
        let timeline = try await notification(tid)["timeline"] as? [Any] ?? []
        return timeline.contains { entry in
            guard let data = try? JSONSerialization.data(withJSONObject: entry),
                  let text = String(data: data, encoding: .utf8) else { return false }
            return text.contains("\"\(state)\"")
        }
    }

    /// Fallback receiver calls of the app for the tracking id.
    func inboxCalls(appId: String, tid: String) async throws -> Int {
        let calls = try await getObject("/console/api/inbox?appId=\(enc(appId))")["calls"] as? [[String: Any]] ?? []
        return calls.filter { ($0["trackingId"] as? String) == tid }.count
    }

    /// Backend log entries with `event.action` = `action`, newest first.
    func logs(_ action: String) async throws -> [[String: Any]] {
        try await getArray("/console/api/logs?eventAction=\(enc(action))&limit=500")
    }

    /// The device-traffic proxy (127.0.0.1:8091 -> backend): offline closes every connection, so the app sees a
    /// transport error.
    func setDeviceNetwork(online: Bool) async throws {
        let json = try await post("/console/api/device-network", ["online": online], expect: 200)
        guard json["online"] as? Bool == online else { throw HarnessError.unexpected("device-network: \(json)") }
    }

    // MARK: HTTP

    private func getObject(_ path: String) async throws -> [String: Any] {
        guard let obj = try JSONSerialization.jsonObject(with: try await call("GET", path, nil, 200)) as? [String: Any]
        else { throw HarnessError.unexpected("GET \(path): not a JSON object") }
        return obj
    }

    private func getArray(_ path: String) async throws -> [[String: Any]] {
        guard let arr = try JSONSerialization.jsonObject(with: try await call("GET", path, nil, 200)) as? [[String: Any]]
        else { throw HarnessError.unexpected("GET \(path): not a JSON array") }
        return arr
    }

    private func post(_ path: String, _ body: [String: Any], expect: Int) async throws -> [String: Any] {
        let data = try await call("POST", path, try JSONSerialization.data(withJSONObject: body), expect)
        return try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
    }

    private func call(_ method: String, _ path: String, _ body: Data?, _ expected: Int) async throws -> Data {
        guard let url = URL(string: baseURL.absoluteString + path) else { throw HarnessError.unexpected("bad path \(path)") }
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == expected else {
            throw HarnessError.status("\(method) \(path) answered \(status): \(String(decoding: data.prefix(300), as: UTF8.self))")
        }
        return data
    }

    private func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s
    }
}

enum HarnessError: Error, CustomStringConvertible {
    case status(String)
    case unexpected(String)

    var description: String {
        switch self {
        case let .status(s), let .unexpected(s): s
        }
    }
}

/// Polls `condition` every 250 ms until it is true; fails the test after `seconds` with `what` (and the last error).
@MainActor
func awaitUntil(_ seconds: Double, _ what: @autoclosure () -> String, file: StaticString = #filePath,
                line: UInt = #line, _ condition: () async throws -> Bool) async throws {
    let deadline = Date().addingTimeInterval(seconds)
    var lastError: (any Error)?
    while true {
        do {
            if try await condition() { return }
            lastError = nil
        } catch {
            lastError = error
        }
        if Date() > deadline {
            let suffix = lastError.map { " (last error: \($0))" } ?? ""
            XCTFail("expected within \(Int(seconds)) s: \(what())\(suffix)", file: file, line: line)
            throw HarnessError.unexpected("timed out: \(what())")
        }
        try await Task.sleep(nanoseconds: 250_000_000)
    }
}

/// The sample app driven through its UI: launch (optionally with `RP_RESET=1`), the notification prompt, Register,
/// Logout and the debug push URL (`rpsample://debug-push`, the in-process NSE path).
@MainActor
final class SampleUnderTest {
    let app = XCUIApplication()
    let harness = Harness()
    let appId: String
    let userId: String
    private(set) var deviceId: String?
    /// When the app showed the device id (just after the backend issued the device token).
    private(set) var registeredAt = Date.distantPast

    init(appId: String = E2EConfig.shop) {
        self.appId = appId
        userId = "ios-e2e-\(UUID().uuidString.lowercased())"
    }

    /// Launches (or relaunches: a running app is terminated first) with the E2E environment.
    func launch(reset: Bool) {
        app.launchEnvironment = [
            "RP_BASE_URL": E2EConfig.baseURL,
            "RP_HARNESS_URL": E2EConfig.harnessURL,
            "RP_APP_ID": appId,
            "RP_USER_ID": userId,
            "RP_RESET": reset ? "1" : "0",
        ]
        app.launch()
        // a later launch that reuses this environment without launch(reset:) must not wipe the registration again
        app.launchEnvironment["RP_RESET"] = "0"
        XCTAssertTrue(app.buttons["register"].waitForExistence(timeout: 20), "the sample's main screen")
    }

    /// Answers the system notification prompt if it shows (only on the first launch after an install) and waits until
    /// the app reports a decided authorization; returns it (`authorized`, `denied`, …).
    @discardableResult
    func settleNotificationPrompt(allow: Bool, timeout: Double = 20) async throws -> String {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let label = app.staticTexts["notificationAuth"]
        var status = ""
        try await awaitUntil(timeout, "a decided notification authorization (last: '\(status)')") {
            let alert = springboard.alerts.firstMatch
            if alert.exists {
                // en-US labels (the script sets the Simulator language); by position otherwise: Don't Allow, Allow
                let named = alert.buttons[allow ? "Allow" : "Don't Allow"]
                let button = named.exists ? named : alert.buttons.element(boundBy: allow ? 1 : 0)
                if button.exists { button.tap() }
            }
            status = label.exists ? label.label : ""
            return ["authorized", "denied", "provisional", "ephemeral"].contains(status)
        }
        return status
    }

    /// Taps Register (harness grant -> SDK register -> real device announced to the harness) and waits for both the
    /// device id on screen and the REAL device entry at the harness.
    @discardableResult
    func register() async throws -> String {
        app.buttons["register"].tap()
        let label = app.staticTexts["deviceId"]
        var shown = ""
        try await awaitUntil(30, "a device id on screen after Register (shown: '\(shown)', status: '\(status)')") {
            shown = label.exists ? label.label : ""
            return UUID(uuidString: shown) != nil
        }
        registeredAt = Date()
        deviceId = shown
        try await awaitUntil(15, "the REAL device \(shown) of \(userId) at the harness") {
            try await harness.realDeviceId(appId: appId, userId: userId) == shown
        }
        return shown
    }

    /// Taps Logout and waits until the app shows that the backend confirmed the revoke.
    func logout() async throws {
        app.buttons["logout"].tap()
        try await awaitUntil(30, "status 'logged out (confirmed)' (status: '\(status)')") {
            status == "logged out (confirmed)"
        }
        XCTAssertEqual(app.staticTexts["deviceId"].label, "not registered")
    }

    /// Opens `rpsample://debug-push?...` in the running app: the same `ReliablePushExtension` code as the NSE runs in
    /// the app process, then the content is added as a local notification. Waits until the app reports it handled.
    ///
    /// Through the system (`XCUIDevice.system.open`, like `xcrun simctl openurl`), not `XCUIApplication.open(_:)`: the
    /// latter terminates and relaunches the app, which costs about 2 s (too much for the token-refresh window) and
    /// replays the launch environment.
    ///
    /// `delay` (seconds): the app shows the notification that much later (after the test pressed Home, for a banner).
    func debugPush(_ tid: String, title: String, body: String = "iOS E2E", delay: Int = 0) async throws {
        var components = URLComponents()
        components.scheme = "rpsample"
        components.host = "debug-push"
        components.queryItems = [
            URLQueryItem(name: "rp_tid", value: tid),
            URLQueryItem(name: "title", value: title),
            URLQueryItem(name: "body", value: body),
        ]
        if delay > 0 { components.queryItems?.append(URLQueryItem(name: "delay", value: String(delay))) }
        let pid = runningPid
        XCUIDevice.shared.system.open(components.url!)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let label = app.staticTexts["lastPush"]
        try await awaitUntil(20, "the app handled the debug push \(tid) (lastPush: '\(label.exists ? label.label : "")')") {
            // a confirmation ("Open in …?") would block the URL: accept it
            let open = springboard.alerts.buttons["Open"]
            if open.exists { open.tap() }
            return label.exists && label.label == "handled \(tid)"
        }
        XCTAssertEqual(runningPid, pid, "the debug push ran in the same app process")
    }

    /// The app's process id as it shows it (accessibility id `pid`).
    var runningPid: String {
        let label = app.staticTexts["pid"]
        return label.exists ? label.label : ""
    }

    /// The device id label (`not registered` when the SDK has none).
    var shownDeviceId: String {
        let label = app.staticTexts["deviceId"]
        return label.exists ? label.label : ""
    }

    var status: String {
        let label = app.staticTexts["status"]
        return label.exists ? label.label : ""
    }

    // MARK: Harness expectations

    func awaitState(_ tid: String, _ expected: String, _ seconds: Double) async throws {
        var last: String?
        try await awaitUntil(seconds, "state \(expected) for \(tid) (last seen: \(last ?? "nil"))") {
            last = try await harness.state(tid)
            return last == expected
        }
    }

    /// Waits for `expected` webhook calls, then 2 s more: a second call would follow quickly; there must be exactly one.
    func awaitWebhooks(_ tid: String, _ expected: Int) async throws {
        var last = -1
        try await awaitUntil(15, "\(expected) webhook call(s) for \(tid) (last seen: \(last))") {
            last = try await harness.webhookCount(tid)
            return last >= expected
        }
        try await Task.sleep(nanoseconds: 2_000_000_000)
        let count = try await harness.webhookCount(tid)
        XCTAssertEqual(count, expected, "webhook calls for \(tid)")
    }
}
