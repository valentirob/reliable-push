import XCTest
@testable import ReliablePush

final class DeviceAPITests: XCTestCase {
    private let base = URL(string: "https://rp.example")!

    override func setUp() { StubURLProtocol.reset() }
    override func tearDown() { StubURLProtocol.reset() }

    private func makeAPI(timeout: TimeInterval = 0.5) -> DeviceAPI {
        DeviceAPI(baseURL: base, appId: "app-1", userAgent: "reliable-push-ios/test",
                  configuration: StubURLProtocol.configuration(), timeout: timeout)
    }

    private func json(_ s: String) -> Data { Data(s.utf8) }

    private func jsonBody(_ r: RecordedRequest) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: r.body) as? [String: Any])
    }

    func testRegisterSendsAppIdIdTokenPlatformAndParsesToken() async throws {
        StubURLProtocol.reset { _ in
            .response(status: 201, headers: ["Content-Type": "application/json"],
                      body: Data(#"{"deviceId":"d-1","deviceToken":"tok","expiresAt":"2026-11-05T10:00:00.250Z"}"#.utf8))
        }
        let result = await makeAPI().register(idToken: "idt", deviceId: nil)
        guard case let .ok(reg) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(reg.deviceId, "d-1")
        XCTAssertEqual(reg.deviceToken, "tok")
        XCTAssertEqual(reg.expiresAt.timeIntervalSince1970, 1_793_872_800.25, accuracy: 0.001)

        let req = try XCTUnwrap(StubURLProtocol.requests.first)
        XCTAssertEqual(req.method, "POST")
        XCTAssertEqual(req.url.absoluteString, "https://rp.example/v1/devices")
        XCTAssertEqual(req.headers["Content-Type"], "application/json")
        XCTAssertEqual(req.headers["Accept"], "application/json, application/problem+json")
        XCTAssertEqual(req.headers["User-Agent"], "reliable-push-ios/test")
        XCTAssertNil(req.headers["Authorization"])
        let body = try jsonBody(req)
        XCTAssertEqual(body["appId"] as? String, "app-1")
        XCTAssertEqual(body["idToken"] as? String, "idt")
        XCTAssertEqual(body["platform"] as? String, "IOS")
        XCTAssertNil(body["deviceId"])
    }

    func testRegisterAcceptsDatesWithoutFractionalSeconds() async {
        StubURLProtocol.reset { _ in
            .response(status: 201, body: Data(#"{"deviceId":"d","deviceToken":"t","expiresAt":"2026-11-05T10:00:00Z"}"#.utf8))
        }
        guard case let .ok(reg) = await makeAPI().register(idToken: "i", deviceId: nil) else { return XCTFail() }
        XCTAssertEqual(reg.expiresAt.timeIntervalSince1970, 1_793_872_800, accuracy: 0.001)
    }

    func testRegisterReusesStoredDeviceId() async throws {
        StubURLProtocol.reset { _ in
            .response(status: 201, body: Data(#"{"deviceId":"d-9","deviceToken":"t","expiresAt":"2026-11-05T10:00:00Z"}"#.utf8))
        }
        _ = await makeAPI().register(idToken: "idt", deviceId: "d-9")
        let body = try jsonBody(try XCTUnwrap(StubURLProtocol.requests.first))
        XCTAssertEqual(body["deviceId"] as? String, "d-9")
    }

    func testRefreshSendsBearerToken() async throws {
        StubURLProtocol.reset { _ in
            .response(status: 200, body: Data(#"{"deviceToken":"new","expiresAt":"2026-11-05T10:00:00Z"}"#.utf8))
        }
        guard case let .ok(issued) = await makeAPI().refresh(token: "old") else { return XCTFail() }
        XCTAssertEqual(issued.deviceToken, "new")
        let req = try XCTUnwrap(StubURLProtocol.requests.first)
        XCTAssertEqual(req.method, "POST")
        XCTAssertEqual(req.url.absoluteString, "https://rp.example/v1/devices/token:refresh")
        XCTAssertEqual(req.headers["Authorization"], "Bearer old")
    }

    func testRevokeExpects204() async throws {
        StubURLProtocol.reset { _ in .response(status: 204) }
        guard case .ok = await makeAPI().revoke(token: "tok") else { return XCTFail() }
        let req = try XCTUnwrap(StubURLProtocol.requests.first)
        XCTAssertEqual(req.method, "DELETE")
        XCTAssertEqual(req.url.absoluteString, "https://rp.example/v1/devices/me")
        XCTAssertEqual(req.headers["Authorization"], "Bearer tok")

        StubURLProtocol.reset { _ in .response(status: 200) }
        guard case .http(200, nil) = await makeAPI().revoke(token: "tok") else { return XCTFail("200 is not the contract") }
    }

    func testSendEventsParsesPerItemOutcomes() async throws {
        StubURLProtocol.reset { _ in
            .response(status: 200, body: Data(#"{"items":[{"trackingId":"t1","outcome":"ACCEPTED"},{"trackingId":"t2","outcome":"DUPLICATE"}]}"#.utf8))
        }
        let events = [
            QueuedEvent(trackingId: "t1", type: .delivered, occurredAt: Date(timeIntervalSince1970: 1_793_872_800.5)),
            QueuedEvent(trackingId: "t2", type: .read, occurredAt: Date(timeIntervalSince1970: 1_793_872_801)),
        ]
        guard case let .ok(outcomes) = await makeAPI().sendEvents(token: "tok", events: events) else { return XCTFail() }
        XCTAssertEqual(outcomes, [EventOutcome(trackingId: "t1", outcome: "ACCEPTED"), EventOutcome(trackingId: "t2", outcome: "DUPLICATE")])

        let req = try XCTUnwrap(StubURLProtocol.requests.first)
        XCTAssertEqual(req.url.absoluteString, "https://rp.example/v1/events")
        XCTAssertEqual(req.headers["Authorization"], "Bearer tok")
        let items = try XCTUnwrap(try jsonBody(req)["events"] as? [[String: Any]])
        XCTAssertEqual(items.count, 2)
        XCTAssertEqual(items[0]["trackingId"] as? String, "t1")
        XCTAssertEqual(items[0]["type"] as? String, "DELIVERED")
        XCTAssertEqual(items[0]["occurredAt"] as? String, "2026-11-05T10:00:00.500Z")
        XCTAssertEqual(items[1]["type"] as? String, "READ")
    }

    func testRetryAfterIsParsedOn429() async {
        StubURLProtocol.reset { _ in .response(status: 429, headers: ["Retry-After": "120"]) }
        guard case .http(429, 120) = await makeAPI().sendEvents(token: "t", events: []) else { return XCTFail() }

        StubURLProtocol.reset { _ in .response(status: 429, headers: ["Retry-After": "soon"]) }
        guard case .http(429, nil) = await makeAPI().sendEvents(token: "t", events: []) else { return XCTFail() }

        StubURLProtocol.reset { _ in .response(status: 429, headers: ["Retry-After": "-5"]) }
        guard case .http(429, nil) = await makeAPI().sendEvents(token: "t", events: []) else { return XCTFail() }
    }

    func testServerErrorAndTimeoutMapToHttpAndNetworkError() async {
        StubURLProtocol.reset { _ in .response(status: 503, headers: ["Retry-After": "7"]) }
        guard case .http(503, 7) = await makeAPI().refresh(token: "t") else { return XCTFail() }

        StubURLProtocol.reset { _ in .hang }
        let start = Date()
        let result = await makeAPI(timeout: 0.5).refresh(token: "t")
        guard case .networkError = result else { return XCTFail("\(result)") }
        XCTAssertLessThan(Date().timeIntervalSince(start), 3)
    }

    func testMalformedSuccessBodyIsNetworkError() async {
        StubURLProtocol.reset { _ in .response(status: 201, body: Data("{}".utf8)) }
        guard case .networkError = await makeAPI().register(idToken: "i", deviceId: nil) else { return XCTFail() }
    }

    func testDoesNotFollowRedirects() async {
        StubURLProtocol.reset { _ in .redirect(status: 302, to: "https://evil.example/v1/events") }
        let result = await makeAPI().sendEvents(token: "secret", events: [])
        guard case .http(302, nil) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(StubURLProtocol.requests.count, 1, "the redirect target must never be called")
    }

    func testBodyIsCappedAt64KB() async {
        let big = Data(repeating: UInt8(ascii: "a"), count: 65_537)
        StubURLProtocol.reset { _ in .response(status: 200, body: big) }
        guard case .networkError = await makeAPI().sendEvents(token: "t", events: []) else { return XCTFail("actual body") }

        StubURLProtocol.reset { _ in .response(status: 200, headers: ["Content-Length": "70000"], body: Data("{}".utf8)) }
        guard case .networkError = await makeAPI().sendEvents(token: "t", events: []) else { return XCTFail("declared length") }

        // exactly at the cap is accepted
        let pad = String(repeating: " ", count: 65_536 - 2)
        StubURLProtocol.reset { _ in .response(status: 200, body: Data(("{}" + pad).utf8)) }
        guard case .ok = await makeAPI().sendEvents(token: "t", events: []) else { return XCTFail("at cap") }
    }
}
