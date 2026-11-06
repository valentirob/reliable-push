import XCTest
@testable import ReliablePush

final class EventSenderTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)
    private var dir: URL!
    private var state: SharedState!
    private var secrets: FakeSecretStore!
    private var tokens: TokenStore!
    private var queue: EventQueue!
    private var flushLock: FileLock!
    private var log: WarnBox!
    private let clock = Locked(Date(timeIntervalSince1970: 1_700_000_000))

    /// Healthy token: 97 % of its lifetime left.
    private var goodToken: String { DeviceTokenTests.token(iat: 1_700_000_000 - 100, exp: 1_700_000_000 + 3500) }
    /// Expiring token: under 20 % left.
    private var staleToken: String { DeviceTokenTests.token(iat: 1_700_000_000 - 3500, exp: 1_700_000_000 + 100) }
    private var freshToken: String { DeviceTokenTests.token(iat: 1_700_000_000, exp: 1_700_000_000 + 3600) }

    override func setUp() {
        RefreshGate.resetForTesting()
        StubURLProtocol.reset()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("rp-sender-\(UUID().uuidString)/ReliablePush")
        state = SharedState(defaults: UserDefaults(suiteName: "rp.test.\(UUID().uuidString)")!)
        secrets = FakeSecretStore()
        tokens = TokenStore(secrets: secrets, state: state, appId: "app1")
        queue = EventQueue(directory: dir)
        flushLock = FileLock(url: dir.appendingPathComponent("flush.lock"))
        log = WarnBox()
        let box = log!
        SDKLog.sink = { level, message in box.add("\(level) \(message)") }
        register(goodToken)
    }

    override func tearDown() {
        SDKLog.sink = nil
        StubURLProtocol.reset()
        try? FileManager.default.removeItem(at: dir.deletingLastPathComponent())
    }

    private func register(_ token: String) {
        XCTAssertTrue(tokens.save(Registration(deviceId: "dev1", deviceToken: token, expiresAt: now.addingTimeInterval(3600))))
    }

    private func makeSender() -> EventSender {
        let api = DeviceAPI(baseURL: URL(string: "http://localhost:8080")!, appId: "app1", userAgent: "reliable-push-ios/test",
                            configuration: StubURLProtocol.configuration(), timeout: 2)
        let c = clock
        return EventSender(api: api, tokens: tokens, state: state, queue: queue, flushLock: flushLock, now: { c.withLock { $0 } })
    }

    private func add(_ n: Int, prefix: String = "t") {
        for i in 0..<n { queue.add(QueuedEvent(trackingId: "\(prefix)\(i)", type: .delivered, occurredAt: now)) }
    }

    private static func items(_ pairs: [(String, String)]) -> StubURLProtocol.Reply {
        let list = pairs.map { #"{"trackingId":"\#($0.0)","outcome":"\#($0.1)"}"# }.joined(separator: ",")
        return .response(status: 200, body: Data(#"{"items":[\#(list)]}"#.utf8))
    }

    private static func ids(_ r: RecordedRequest) -> [String] {
        let obj = try? JSONSerialization.jsonObject(with: r.body) as? [String: Any]
        return ((obj?["events"] as? [[String: Any]]) ?? []).compactMap { $0["trackingId"] as? String }
    }

    private func eventRequests() -> [RecordedRequest] { StubURLProtocol.requests.filter { $0.url.path == "/v1/events" } }

    func testEmptyQueueIsDone() async {
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
    }

    func testNotRegisteredKeepsEventsAndWarns() async {
        tokens.clear()
        add(2)
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .notRegistered)
        XCTAssertEqual(queue.count, 2)
        XCTAssertTrue(log.messages.contains { $0.contains("tracking.not_registered") })
    }

    func testUnavailableKeychainKeepsEvents() async {
        add(2)
        secrets.force(.unavailable)
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        XCTAssertEqual(queue.count, 2)
    }

    func testFlushRemovesAcceptedDuplicateLateNotFoundForbidden() async {
        add(5)
        StubURLProtocol.reset { _ in
            Self.items([("t0", "ACCEPTED"), ("t1", "DUPLICATE"), ("t2", "LATE"), ("t3", "NOT_FOUND"), ("t4", "FORBIDDEN")])
        }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
        XCTAssertEqual(queue.count, 0)
        let req = eventRequests()[0]
        XCTAssertEqual(req.headers["Authorization"], "Bearer \(goodToken)")
        XCTAssertEqual(log.messages.filter { $0.contains("tracking.event_sent") }.count, 3)
        XCTAssertTrue(log.messages.contains { $0.contains("info event=tracking.event_sent") && $0.contains("trackingId=t0") })
        XCTAssertEqual(log.messages.filter { $0.hasPrefix("warn") && $0.contains("tracking.event_dropped") }.count, 2)
        XCTAssertFalse(log.messages.contains { $0.contains(goodToken) })
    }

    func testUnknownOutcomeOrMissingItemDroppedAfterSecondFlush() async {
        add(3)
        StubURLProtocol.reset { _ in Self.items([("t0", "ACCEPTED"), ("t1", "WEIRD")]) } // t2 not mentioned
        var r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        XCTAssertEqual(queue.peek(max: 10).map(\.trackingId), ["t1", "t2"])
        XCTAssertEqual(eventRequests().count, 1)
        XCTAssertFalse(log.messages.contains { $0.contains("unacknowledged") })

        r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
        XCTAssertEqual(queue.count, 0)
        for id in ["t1", "t2"] {
            XCTAssertTrue(log.messages.contains { $0.hasPrefix("warn") && $0.contains("reason=unacknowledged") && $0.contains("trackingId=\(id)") })
        }
    }

    func testAcknowledgedEventResetsNothingButIsRemoved() async {
        add(1)
        StubURLProtocol.reset { _ in Self.items([]) }
        _ = await makeSender().flush(deadline: nil)
        StubURLProtocol.reset { _ in Self.items([("t0", "ACCEPTED")]) }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
        XCTAssertFalse(log.messages.contains { $0.contains("unacknowledged") })
    }

    func testRefreshAnswered429StoresRetryNotBeforeAndSendsNothing() async {
        register(staleToken)
        add(1)
        StubURLProtocol.reset { _ in .response(status: 429, headers: ["Retry-After": "90"]) }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        XCTAssertEqual(state.retryNotBefore, now.addingTimeInterval(90))
        XCTAssertEqual(StubURLProtocol.requests.map(\.url.path), ["/v1/devices/token:refresh"])
        XCTAssertEqual(queue.count, 1)
    }

    func testSaveFailureAfterRefreshKeepsEventsRetryLater() async {
        add(1)
        StubURLProtocol.reset { req in
            req.url?.path == "/v1/devices/token:refresh"
                ? .response(status: 200, body: Data(#"{"deviceToken":"not-a-jwt","expiresAt":"2023-11-14T23:13:20Z"}"#.utf8))
                : .response(status: 401)
        }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        XCTAssertEqual(queue.count, 1)
        XCTAssertEqual(StubURLProtocol.requests.map(\.url.path), ["/v1/events", "/v1/devices/token:refresh"])
        guard case .registered = tokens.load() else { return XCTFail() }
    }

    func testDeadlineAlreadyPassedSendsNothing() async {
        add(2)
        let r = await makeSender().flush(deadline: now.addingTimeInterval(-1))
        XCTAssertEqual(r, .retryLater)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
    }

    func testEarlyRefreshConsumedGateThen401IsExhausted() async {
        register(staleToken)
        add(1)
        let fresh = freshToken
        StubURLProtocol.reset { req in
            req.url?.path == "/v1/devices/token:refresh"
                ? .response(status: 200, body: Data(#"{"deviceToken":"\#(fresh)","expiresAt":"2023-11-14T23:13:20Z"}"#.utf8))
                : .response(status: 401)
        }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        XCTAssertEqual(StubURLProtocol.requests.map(\.url.path), ["/v1/devices/token:refresh", "/v1/events"])
        XCTAssertTrue(log.messages.contains { $0.hasPrefix("warn") && $0.contains("auth.failed") && $0.contains("exhausted") })
        XCTAssertEqual(queue.count, 1)
    }

    func testFlushKeepsEventsOn429And5xxAndNetworkError() async {
        add(3)
        StubURLProtocol.reset { _ in .response(status: 429, headers: ["Retry-After": "120"]) }
        var r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        XCTAssertEqual(queue.count, 3)
        XCTAssertEqual(state.retryNotBefore, now.addingTimeInterval(120))
        XCTAssertTrue(log.messages.contains { $0.contains("rate_limit.exceeded") })

        state.retryNotBefore = nil
        StubURLProtocol.reset { _ in .response(status: 429, headers: ["Retry-After": "999999"]) }
        _ = await makeSender().flush(deadline: nil)
        XCTAssertEqual(state.retryNotBefore, now.addingTimeInterval(3600))

        state.retryNotBefore = nil
        StubURLProtocol.reset { _ in .response(status: 429) }
        r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        XCTAssertNil(state.retryNotBefore)

        for status in [500, 503, 408] {
            StubURLProtocol.reset { _ in .response(status: status) }
            r = await makeSender().flush(deadline: nil)
            XCTAssertEqual(r, .retryLater, "status \(status)")
            XCTAssertEqual(queue.count, 3)
        }
        StubURLProtocol.reset { _ in .redirect(status: 302, to: "http://evil.example/x") }
        r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)

        // transport error: the stub hangs, the 0.2 s request timeout fires
        let api = DeviceAPI(baseURL: URL(string: "http://localhost:8080")!, appId: "app1", userAgent: "x",
                            configuration: StubURLProtocol.configuration(), timeout: 0.2)
        StubURLProtocol.reset { _ in .hang }
        let sender = EventSender(api: api, tokens: tokens, state: state, queue: queue, flushLock: flushLock, now: { Date(timeIntervalSince1970: 1_700_000_000) })
        r = await sender.flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        XCTAssertEqual(queue.count, 3)
    }

    func testSuccessfulFlushClearsRetryNotBefore() async {
        add(1)
        state.retryNotBefore = now.addingTimeInterval(-5)
        StubURLProtocol.reset { _ in Self.items([("t0", "ACCEPTED")]) }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
        XCTAssertNil(state.retryNotBefore)
    }

    func testFlushSkipsBeforeRetryNotBefore() async {
        add(1)
        state.retryNotBefore = now.addingTimeInterval(60)
        StubURLProtocol.reset { _ in Self.items([("t0", "ACCEPTED")]) }
        var r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .skipped)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
        clock.withLock { $0 = $0.addingTimeInterval(61) }
        r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
    }

    func testRetryNotBeforeFurtherThanTheCapIsExpiredAndCleared() async {
        // stored while the device clock was days ahead; the clock was corrected since
        add(1)
        state.retryNotBefore = now.addingTimeInterval(2 * 86_400)
        StubURLProtocol.reset { _ in Self.items([("t0", "ACCEPTED")]) }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
        XCTAssertNil(state.retryNotBefore)

        // within the cap (3600 s) it is honoured
        add(1, prefix: "u")
        state.retryNotBefore = now.addingTimeInterval(3600)
        let skipped = await makeSender().flush(deadline: nil)
        XCTAssertEqual(skipped, .skipped)
        XCTAssertEqual(state.retryNotBefore, now.addingTimeInterval(3600))
    }

    func testFlushDropsBatchOnOther4xx() async {
        add(3)
        StubURLProtocol.reset { _ in .response(status: 400) }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
        XCTAssertEqual(queue.count, 0)
        XCTAssertTrue(log.messages.contains { $0.hasPrefix("warn") && $0.contains("tracking.event_dropped") && $0.contains("count=3") })
    }

    func testFlushBatchesFifty() async {
        add(120)
        StubURLProtocol.reset { req in
            let body = (req.httpBodyStream.map { s -> Data in
                s.open(); defer { s.close() }
                var d = Data(); var buf = [UInt8](repeating: 0, count: 4096)
                while s.hasBytesAvailable { let n = s.read(&buf, maxLength: 4096); if n <= 0 { break }; d.append(buf, count: n) }
                return d
            }) ?? req.httpBody ?? Data()
            let obj = try? JSONSerialization.jsonObject(with: body) as? [String: Any]
            let ids = ((obj?["events"] as? [[String: Any]]) ?? []).compactMap { $0["trackingId"] as? String }
            return Self.items(ids.map { ($0, "ACCEPTED") })
        }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
        XCTAssertEqual(eventRequests().map { Self.ids($0).count }, [50, 50, 20])
        XCTAssertEqual(queue.count, 0)
    }

    func testOn401RefreshesOnceAndRetries() async {
        add(1)
        let fresh = freshToken
        StubURLProtocol.reset { req in
            if req.url?.path == "/v1/devices/token:refresh" {
                return .response(status: 200, body: Data(#"{"deviceToken":"\#(fresh)","expiresAt":"2023-11-14T23:13:20Z"}"#.utf8))
            }
            if req.value(forHTTPHeaderField: "Authorization") == "Bearer \(fresh)" { return Self.items([("t0", "ACCEPTED")]) }
            return .response(status: 401)
        }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
        XCTAssertEqual(StubURLProtocol.requests.map(\.url.path), ["/v1/events", "/v1/devices/token:refresh", "/v1/events"])
        guard case let .registered(deviceId, token) = tokens.load() else { return XCTFail() }
        XCTAssertEqual(deviceId, "dev1")
        XCTAssertEqual(token.raw, fresh)
        XCTAssertTrue(log.messages.contains { $0.hasPrefix("info") && $0.contains("token.refreshed") })
    }

    func testRetryStillUnauthorizedKeepsEvents() async {
        add(1)
        let fresh = freshToken
        StubURLProtocol.reset { req in
            req.url?.path == "/v1/devices/token:refresh"
                ? .response(status: 200, body: Data(#"{"deviceToken":"\#(fresh)","expiresAt":"2023-11-14T23:13:20Z"}"#.utf8))
                : .response(status: 401)
        }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        XCTAssertEqual(queue.count, 1)
        XCTAssertTrue(log.messages.contains { $0.hasPrefix("warn") && $0.contains("auth.failed") })
    }

    func testRefreshAnswered401ClearsRegistration() async {
        add(2)
        StubURLProtocol.reset { _ in .response(status: 401) }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .notRegistered)
        guard case .notRegistered = tokens.load() else { return XCTFail() }
        XCTAssertEqual(queue.count, 2)
    }

    func testRefreshFailingWith5xxKeepsRegistrationAndEvents() async {
        add(2)
        StubURLProtocol.reset { req in req.url?.path == "/v1/devices/token:refresh" ? .response(status: 503) : .response(status: 401) }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        guard case .registered = tokens.load() else { return XCTFail() }
        XCTAssertEqual(queue.count, 2)
    }

    func testSecondRefreshInSameProcessIsNotAttempted() async {
        add(1)
        XCTAssertTrue(RefreshGate.tryAcquire())
        StubURLProtocol.reset { _ in .response(status: 401) }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .retryLater)
        XCTAssertEqual(StubURLProtocol.requests.map(\.url.path), ["/v1/events"])
        XCTAssertEqual(queue.count, 1)
        XCTAssertTrue(log.messages.contains { $0.hasPrefix("warn") && $0.contains("auth.failed") })
        guard case .registered = tokens.load() else { return XCTFail() }
    }

    func testRefreshesFirstWhenTokenIsStale() async {
        register(staleToken)
        add(1)
        let fresh = freshToken
        StubURLProtocol.reset { req in
            if req.url?.path == "/v1/devices/token:refresh" {
                return .response(status: 200, body: Data(#"{"deviceToken":"\#(fresh)","expiresAt":"2023-11-14T23:13:20Z"}"#.utf8))
            }
            return Self.items([("t0", "ACCEPTED")])
        }
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .done)
        XCTAssertEqual(StubURLProtocol.requests.map(\.url.path), ["/v1/devices/token:refresh", "/v1/events"])
        XCTAssertEqual(eventRequests()[0].headers["Authorization"], "Bearer \(fresh)")
    }

    func testFlushLockSkipsWhenHeld() async {
        add(1)
        let other = FileLock(url: dir.appendingPathComponent("flush.lock"))
        let held = other.tryLock()
        XCTAssertNotNil(held)
        let r = await makeSender().flush(deadline: nil)
        XCTAssertEqual(r, .skipped)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
        held?.release()
        StubURLProtocol.reset { _ in Self.items([("t0", "ACCEPTED")]) }
        let again = await makeSender().flush(deadline: nil)
        XCTAssertEqual(again, .done)
    }

    // M7: a flush with a deadline waits for a held lock instead of skipping, so an event queued while another process
    // flushes is sent by this run, not left for the next push or launch.
    func testFlushWithDeadlineWaitsForHeldLockThenSends() async {
        let other = FileLock(url: dir.appendingPathComponent("flush.lock"))
        let held = Locked<FileLock.Held?>(other.tryLock())
        XCTAssertNotNil(held.withLock { $0 })
        add(1) // queued while the other holder is busy
        StubURLProtocol.reset { _ in Self.items([("t0", "ACCEPTED")]) }
        let sender = makeSender()
        let deadline = now.addingTimeInterval(5)
        let flush = Task { await sender.flush(deadline: deadline) }
        try? await Task.sleep(nanoseconds: 150_000_000)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty, "nothing is sent while the lock is held")
        held.withLock { $0?.release(); $0 = nil }
        let r = await flush.value
        XCTAssertEqual(r, .done)
        XCTAssertEqual(eventRequests().count, 1)
        XCTAssertEqual(queue.count, 0)
    }

    func testFlushWithDeadlineSkipsWhenLockIsHeldPastTheDeadline() async {
        add(1)
        let other = FileLock(url: dir.appendingPathComponent("flush.lock"))
        let held = other.tryLock()
        XCTAssertNotNil(held)
        let c = clock
        let sender = makeSender()
        let deadline = now.addingTimeInterval(5)
        let flush = Task { await sender.flush(deadline: deadline) }
        try? await Task.sleep(nanoseconds: 100_000_000)
        c.withLock { $0 = $0.addingTimeInterval(10) } // the deadline passes while the lock is still held
        let r = await flush.value
        XCTAssertEqual(r, .skipped)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
        XCTAssertEqual(queue.count, 1)
        held?.release()
    }

    func testCancellingAFlushThatWaitsForTheLockReturnsSkipped() async {
        add(1)
        let other = FileLock(url: dir.appendingPathComponent("flush.lock"))
        let held = other.tryLock()
        XCTAssertNotNil(held)
        let sender = makeSender()
        let deadline = now.addingTimeInterval(5)
        let flush = Task { await sender.flush(deadline: deadline) }
        try? await Task.sleep(nanoseconds: 100_000_000)
        flush.cancel()
        let r = await flush.value
        XCTAssertEqual(r, .skipped)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
        held?.release()
    }

    func testDeadlineStopsBetweenBatches() async {
        add(120)
        let c = clock
        StubURLProtocol.reset { req in
            // the first batch takes "time": move the injected clock past the deadline
            c.withLock { $0 = $0.addingTimeInterval(10) }
            let body = (req.httpBodyStream.map { s -> Data in
                s.open(); defer { s.close() }
                var d = Data(); var buf = [UInt8](repeating: 0, count: 4096)
                while s.hasBytesAvailable { let n = s.read(&buf, maxLength: 4096); if n <= 0 { break }; d.append(buf, count: n) }
                return d
            }) ?? Data()
            let obj = try? JSONSerialization.jsonObject(with: body) as? [String: Any]
            let ids = ((obj?["events"] as? [[String: Any]]) ?? []).compactMap { $0["trackingId"] as? String }
            return Self.items(ids.map { ($0, "ACCEPTED") })
        }
        let r = await makeSender().flush(deadline: now.addingTimeInterval(5))
        XCTAssertEqual(r, .retryLater)
        XCTAssertEqual(eventRequests().count, 1)
        XCTAssertEqual(queue.count, 70)
    }
}

