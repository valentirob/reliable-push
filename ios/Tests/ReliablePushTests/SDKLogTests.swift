import XCTest
import UserNotifications
@testable import ReliablePush

final class SDKLogTests: XCTestCase {
    private var savedDebug = SDKLog.debug

    override func setUp() { savedDebug = SDKLog.debug }

    override func tearDown() {
        SDKLog.sink = nil
        SDKLog.debug = savedDebug
        SDK.resetForTesting()
        StubURLProtocol.reset()
    }

    func testFormatsEventAndFieldsAndDropsDeniedKeys() {
        let box = WarnBox()
        SDKLog.sink = { level, m in box.add("\(level):\(m)") }
        SDKLog.warn("x.y", ["deviceId": "d1", "token": "SECRET", "idToken": "SECRET2"])
        XCTAssertEqual(box.messages, ["warn:event=x.y deviceId=d1"])
    }

    func testReleaseLoggingOnlyWarnAndAbove() {
        let box = WarnBox()
        SDKLog.sink = { level, m in box.add("\(level):\(m)") }
        SDKLog.debug = false
        SDKLog.debug("a.debug")
        SDKLog.info("a.info", ["trackingId": "t1"])
        SDKLog.warn("a.warn", ["trackingId": "t1"])
        XCTAssertEqual(box.messages, ["warn:event=a.warn trackingId=t1"])

        SDKLog.debug = true
        SDKLog.debug("b.debug")
        SDKLog.info("b.info")
        XCTAssertEqual(box.messages.count, 3)
    }

    /// Drives register, the NSE path, trackOpen and logout with every level enabled: no ID token, device token,
    /// bearer header or notification text may reach the log.
    func testNoTokenOrContentInLogs() async throws {
        SDK.resetForTesting()
        RefreshGate.resetForTesting()
        let fx = SDKFixture()
        defer { fx.cleanup() }
        SDKLog.debug = true
        let idToken = "ID-TOKEN-\(UUID().uuidString)"
        try ReliablePush.configure(baseURL: "http://localhost:8080", appId: "app1", environment: fx.environment)
        _ = await SDK.current?.lastFlushForTesting?.value

        StubURLProtocol.reset { _ in .response(status: 401) }
        let rejected = await ReliablePush.register(idToken: idToken)
        XCTAssertEqual(rejected, .failed(httpStatus: 401))

        StubURLProtocol.reset { req in
            switch req.url?.path {
            case "/v1/devices": return SDKFixture.registration(token: SDKFixture.staleToken) // next flush refreshes
            case "/v1/devices/token:refresh": return SDKFixture.refreshed()
            case "/v1/devices/me": return .response(status: 204)
            default: return SDKFixture.acceptAll(req)
            }
        }
        let registered = await ReliablePush.register(idToken: idToken)
        XCTAssertEqual(registered, .success(deviceId: "dev1"))

        let content = UNMutableNotificationContent()
        content.title = "SECRET-TITLE"
        content.body = "SECRET-BODY"
        let tid = UUID().uuidString
        content.userInfo = ["rp_tid": tid]
        let request = UNNotificationRequest(identifier: "n1", content: content, trigger: nil)
        let handled = expectation(description: "handled")
        let ext = ReliablePushExtension(environment: fx.environment, options: .init())
        // didReceive is called from a synchronous override, never from the cooperative pool
        nonisolated(unsafe) let req = request
        DispatchQueue.global().async {
            XCTAssertTrue(ext.didReceive(req, withContentHandler: { _ in handled.fulfill() }))
        }
        await fulfillment(of: [handled], timeout: 5)

        XCTAssertTrue(ReliablePush.trackOpen(userInfo: ["rp_tid": tid], actionIdentifier: UNNotificationDefaultActionIdentifier))
        _ = await SDK.current?.lastFlushForTesting?.value
        let loggedOut = expectation(description: "logout")
        ReliablePush.logout { _ in loggedOut.fulfill() }
        await fulfillment(of: [loggedOut], timeout: 5)

        let all = fx.log.messages
        XCTAssertTrue(all.contains { $0.contains("event=device.registered") && $0.contains("deviceId=dev1") })
        XCTAssertTrue(all.contains { $0.contains("event=tracking.event_queued") && $0.contains("trackingId=\(tid)") })
        XCTAssertTrue(all.contains { $0.contains("event=device.revoked") })
        XCTAssertTrue(all.contains { $0.contains("event=token.refreshed") })
        XCTAssertTrue(all.contains { $0.contains("event=auth.failed") })
        XCTAssertTrue(StubURLProtocol.requests.contains { $0.url.path == "/v1/devices/token:refresh" })
        for secret in [idToken, SDKFixture.staleToken, SDKFixture.freshToken, "Bearer", "SECRET-TITLE", "SECRET-BODY"] {
            XCTAssertFalse(all.contains { $0.contains(secret) }, "\(secret) leaked: \(all)")
        }
    }
}
