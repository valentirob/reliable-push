import XCTest
import UserNotifications
@testable import ReliablePush

final class ReliablePushTests: XCTestCase {
    private var fx: SDKFixture!

    override func setUp() {
        SDK.resetForTesting()
        RefreshGate.resetForTesting()
        StubURLProtocol.reset()
        fx = SDKFixture()
    }

    override func tearDown() {
        SDK.resetForTesting()
        StubURLProtocol.reset()
        fx.cleanup()
    }

    private func configure(appId: String = "app1") throws {
        try ReliablePush.configure(baseURL: "http://localhost:8080", appId: appId, environment: fx.environment)
    }

    @discardableResult
    private func awaitFlush() async -> FlushResult? { await SDK.current?.lastFlushForTesting?.value }

    private func event(_ id: String, _ type: EventType = .delivered) -> QueuedEvent {
        QueuedEvent(trackingId: id, type: type, occurredAt: SDKFixture.now)
    }

    // MARK: configure

    func testConfigureRejectsCleartextRemoteURL() {
        XCTAssertThrowsError(try ReliablePush.configure(baseURL: "http://push.example.com", appId: "app1",
                                                        appGroup: "group.io.reliablepush.test")) {
            XCTAssertEqual($0 as? ReliablePushError, .insecureBaseURL)
        }
        XCTAssertThrowsError(try ReliablePush.configure(baseURL: "http://push.example.com", appId: "app1",
                                                        environment: fx.environment)) {
            XCTAssertEqual($0 as? ReliablePushError, .insecureBaseURL)
        }
        XCTAssertThrowsError(try ReliablePush.configure(baseURL: "https://push.example.com", appId: "  ",
                                                        environment: fx.environment)) {
            XCTAssertEqual($0 as? ReliablePushError, .invalidAppId)
        }
        XCTAssertNil(fx.state.config)
        XCTAssertNil(SDK.current)
        XCTAssertEqual(fx.activity.performed, 0)
    }

    func testAppGroupEnvironmentNeedsDefaultsAndContainer() throws {
        let container = fx.root
        XCTAssertThrowsError(try SDKEnvironment.appGroup("group.x", options: .init(), defaults: { _ in nil },
                                                         container: { _ in container })) {
            XCTAssertEqual($0 as? ReliablePushError, .appGroupUnavailable)
        }
        let suite = fx.suite
        XCTAssertThrowsError(try SDKEnvironment.appGroup("group.x", options: .init(),
                                                         defaults: { _ in UserDefaults(suiteName: suite) },
                                                         container: { _ in nil })) {
            XCTAssertEqual($0 as? ReliablePushError, .appGroupUnavailable)
        }
        let env = try SDKEnvironment.appGroup("group.x", options: .init(), defaults: { _ in UserDefaults(suiteName: suite) },
                                              container: { _ in container })
        XCTAssertEqual(env.queueDirectory.path, container.appendingPathComponent("Library/Application Support/ReliablePush").path)
    }

    func testConfigurePersistsConfigAndSameValuesIsNoOpWithOneFlushPerProcess() async throws {
        fx.storeConfig()
        fx.register()
        fx.queue.add(event("t1"))
        StubURLProtocol.reset { SDKFixture.acceptAll($0) }

        try configure()
        let first = SDK.current
        let r = await awaitFlush()
        XCTAssertEqual(r, .done)
        XCTAssertEqual(SDKFixture.sentEvents(), ["t1|DELIVERED"])
        XCTAssertEqual(StubURLProtocol.requests.first?.headers["User-Agent"], "reliable-push-ios/\(ReliablePush.version)")

        try configure()
        XCTAssertTrue(SDK.current === first)
        XCTAssertEqual(fx.activity.performed, 1)
        XCTAssertEqual(ReliablePush.deviceId, "dev1")
        XCTAssertTrue(fx.secrets.has("app1"))
        XCTAssertEqual(fx.state.config?.appId, "app1")
        XCTAssertEqual(fx.state.config?.baseURL.absoluteString, "http://localhost:8080")
    }

    func testConfigureWithOtherAppIdClearsRegistration() async throws {
        try configure(appId: "app1")
        await awaitFlush()
        fx.register(appId: "app1")
        fx.queue.add(event("t1"))
        fx.state.retryNotBefore = SDKFixture.now.addingTimeInterval(600)
        XCTAssertEqual(ReliablePush.deviceId, "dev1")

        try configure(appId: "app2")
        XCTAssertFalse(fx.secrets.has("app1"))
        XCTAssertNil(fx.state.deviceId)
        XCTAssertNil(ReliablePush.deviceId)
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertNil(fx.state.retryNotBefore)
        XCTAssertEqual(fx.state.config?.appId, "app2")
        XCTAssertEqual(fx.activity.performed, 1, "one launch flush per process")
        XCTAssertTrue(fx.log.messages.contains { $0.contains("event=sdk.reconfigured") })
    }

    func testFirstConfigureWithoutStoredConfigClearsSurvivingKeychainRecord() throws {
        // a reinstall: the App Group defaults are gone, the ThisDeviceOnly Keychain item survived
        fx.register()
        fx.state.clearRegistration()
        XCTAssertTrue(fx.secrets.has("app1"))
        try configure()
        XCTAssertFalse(fx.secrets.has("app1"))
        XCTAssertNil(ReliablePush.deviceId)
        guard case .notRegistered = fx.tokens().load() else { return XCTFail("record survived") }
    }

    // MARK: register / logout

    func testRegisterStoresTokenAndCallsBackOnMain() async throws {
        let before = await ReliablePush.register(idToken: "id-token-1")
        XCTAssertEqual(before, .failed(httpStatus: nil), "register before configure")

        try configure()
        await awaitFlush()
        fx.queue.add(event("t1", .read))
        StubURLProtocol.reset { req in req.url?.path == "/v1/devices" ? SDKFixture.registration() : SDKFixture.acceptAll(req) }

        let done = expectation(description: "completion")
        ReliablePush.register(idToken: "id-token-1") { result in
            XCTAssertTrue(Thread.isMainThread)
            XCTAssertEqual(result, .success(deviceId: "dev1"))
            done.fulfill()
        }
        await fulfillment(of: [done], timeout: 5)

        guard case let .registered(deviceId, token) = fx.tokens().load() else { return XCTFail("not stored") }
        XCTAssertEqual(deviceId, "dev1")
        XCTAssertEqual(token.raw, SDKFixture.goodToken)
        XCTAssertEqual(ReliablePush.deviceId, "dev1")
        let body = try XCTUnwrap(StubURLProtocol.requests.first { $0.url.path == "/v1/devices" }?.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["idToken"] as? String, "id-token-1")
        XCTAssertEqual(json["appId"] as? String, "app1")

        // success flushes the queue
        let r = await awaitFlush()
        XCTAssertEqual(r, .done)
        XCTAssertEqual(SDKFixture.sentEvents(), ["t1|READ"])
        XCTAssertEqual(fx.queue.count, 0)
    }

    func testRegisterFailureReportsStatusAndStoresNothing() async throws {
        try configure()
        await awaitFlush()
        StubURLProtocol.reset { _ in .response(status: 401) }
        let r = await ReliablePush.register(idToken: "id-token-1")
        XCTAssertEqual(r, .failed(httpStatus: 401))
        XCTAssertNil(ReliablePush.deviceId)
        XCTAssertTrue(fx.warned("auth.failed"))
    }

    func testLogoutRevokesAndClearsQueueAndState() async throws {
        try configure()
        await awaitFlush()
        fx.register()
        fx.queue.add(event("t1"))
        fx.state.retryNotBefore = SDKFixture.now.addingTimeInterval(600)
        StubURLProtocol.reset { _ in .response(status: 204) }

        let done = expectation(description: "completion")
        ReliablePush.logout { ok in
            XCTAssertTrue(Thread.isMainThread)
            XCTAssertTrue(ok)
            done.fulfill()
        }
        await fulfillment(of: [done], timeout: 5)

        let req = try XCTUnwrap(StubURLProtocol.requests.first)
        XCTAssertEqual(req.method, "DELETE")
        XCTAssertEqual(req.url.path, "/v1/devices/me")
        XCTAssertEqual(req.headers["Authorization"], "Bearer \(SDKFixture.goodToken)")
        XCTAssertFalse(fx.secrets.has("app1"))
        XCTAssertNil(ReliablePush.deviceId)
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertNil(fx.state.retryNotBefore)
        XCTAssertEqual(fx.state.config?.appId, "app1", "config is kept")

        // a failed revoke still clears everything locally
        fx.register()
        fx.queue.add(event("t2"))
        StubURLProtocol.reset { _ in .response(status: 503) }
        let failed = expectation(description: "completion 2")
        ReliablePush.logout { ok in
            XCTAssertFalse(ok)
            failed.fulfill()
        }
        await fulfillment(of: [failed], timeout: 5)
        XCTAssertFalse(fx.secrets.has("app1"))
        XCTAssertEqual(fx.queue.count, 0)
    }

    func testLogoutDuringRefreshLeavesNoRegistration() async throws {
        fx.storeConfig()
        fx.register(token: SDKFixture.staleToken)
        fx.queue.add(event("t1"))
        StubURLProtocol.reset { req in
            switch req.url?.path {
            case "/v1/devices/token:refresh":
                Thread.sleep(forTimeInterval: 0.5) // the new token arrives after the logout
                return SDKFixture.refreshed()
            case "/v1/devices/me": return .response(status: 204)
            default: return SDKFixture.acceptAll(req)
            }
        }
        try configure() // the launch flush starts with a refresh
        let refreshing = await waitUntilAsync { StubURLProtocol.requests.contains { $0.url.path == "/v1/devices/token:refresh" } }
        XCTAssertTrue(refreshing)

        let confirmed = await withCheckedContinuation { c in ReliablePush.logout { c.resume(returning: $0) } }
        XCTAssertTrue(confirmed)
        try await Task.sleep(nanoseconds: 1_000_000_000) // any late refresh answer has landed by now
        XCTAssertFalse(fx.secrets.has("app1"), "no token saved after logout")
        XCTAssertNil(ReliablePush.deviceId)
        XCTAssertEqual(fx.queue.count, 0)
    }

    func testHeldFlushLockBoundsLogoutAndReconfigureWait() async throws {
        try configure(appId: "app1")
        await awaitFlush()
        fx.register()
        fx.queue.add(event("t1"))
        StubURLProtocol.reset { _ in .response(status: 204) }
        let held = try XCTUnwrap(fx.flushLock.tryLock(), "another process's flush holds the lock")
        defer { held.release() }

        var start = Date()
        let confirmed = await withCheckedContinuation { c in ReliablePush.logout { c.resume(returning: $0) } }
        var elapsed = Date().timeIntervalSince(start)
        XCTAssertTrue(confirmed)
        XCTAssertGreaterThan(elapsed, 1.8, "waited for the lock first")
        XCTAssertLessThan(elapsed, 2.5)
        XCTAssertFalse(fx.secrets.has("app1"))
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertTrue(fx.log.messages.contains { $0.contains("event=lock.unavailable") && $0.contains("reason=timeout") })

        fx.register()
        fx.queue.add(event("t2"))
        start = Date()
        try configure(appId: "app2")
        elapsed = Date().timeIntervalSince(start)
        XCTAssertGreaterThan(elapsed, 1.8)
        XCTAssertLessThan(elapsed, 2.5)
        XCTAssertFalse(fx.secrets.has("app1"))
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertEqual(fx.state.config?.appId, "app2")
    }

    // MARK: trackOpen

    func testTrackOpenSendsReadForTapAndCustomActionNotForDismiss() async throws {
        try configure()
        await awaitFlush()
        fx.register()
        StubURLProtocol.reset { SDKFixture.acceptAll($0) }
        let tap = UUID().uuidString, custom = UUID().uuidString, dismiss = UUID().uuidString

        XCTAssertTrue(ReliablePush.trackOpen(userInfo: ["rp_tid": tap], actionIdentifier: UNNotificationDefaultActionIdentifier))
        await awaitFlush()
        XCTAssertTrue(ReliablePush.trackOpen(userInfo: ["rp_tid": custom], actionIdentifier: "REPLY"))
        await awaitFlush()
        XCTAssertTrue(ReliablePush.trackOpen(userInfo: ["rp_tid": dismiss], actionIdentifier: UNNotificationDismissActionIdentifier))
        XCTAssertEqual(fx.queue.count, 0)
        await awaitFlush()

        XCTAssertFalse(ReliablePush.trackOpen(userInfo: ["other": "x"], actionIdentifier: UNNotificationDefaultActionIdentifier))
        XCTAssertFalse(ReliablePush.trackOpen(userInfo: ["rp_tid": "not-a-uuid"], actionIdentifier: UNNotificationDefaultActionIdentifier))
        XCTAssertTrue(fx.warned("tracking.event_dropped"))

        XCTAssertEqual(SDKFixture.sentEvents(), ["\(tap)|READ", "\(custom)|READ"])
        XCTAssertEqual(fx.activity.performed, 3, "launch flush + one per READ")
    }

    func testTrackOpenNotConfiguredReturnsTrueAndPersistsNothing() {
        XCTAssertTrue(ReliablePush.trackOpen(userInfo: ["rp_tid": UUID().uuidString],
                                             actionIdentifier: UNNotificationDefaultActionIdentifier))
        XCTAssertTrue(fx.warned("tracking.not_registered"))
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertEqual(fx.activity.performed, 0)
    }

    func testTrackOpenNotRegisteredWarnsAndSkips() async throws {
        try configure()
        await awaitFlush()
        XCTAssertTrue(ReliablePush.trackOpen(userInfo: ["rp_tid": UUID().uuidString],
                                             actionIdentifier: UNNotificationDefaultActionIdentifier))
        XCTAssertTrue(fx.warned("tracking.not_registered"))
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertEqual(fx.activity.performed, 1)
    }

    // MARK: app-side flush (R12, 0xdead10cc)

    func testAppFlushExpiryCancelsFlushAndReleasesLock() async throws {
        fx.storeConfig()
        fx.register()
        fx.queue.add(event("t1"))
        StubURLProtocol.reset { _ in .hang }

        try configure()
        let inFlight = await waitUntilAsync { !StubURLProtocol.requests.isEmpty }
        XCTAssertTrue(inFlight)
        XCTAssertNil(fx.flushLock.tryLock(), "flush.lock held during the send")

        let expiredAt = Date()
        fx.activity.expire()
        let r = await awaitFlush()
        XCTAssertEqual(r, .retryLater)
        XCTAssertLessThan(Date().timeIntervalSince(expiredAt), 2, "expiry cancels the in-flight send at once")
        let held = fx.flushLock.tryLock()
        XCTAssertNotNil(held, "a second flush can take the lock")
        held?.release()
        XCTAssertEqual(fx.queue.count, 1, "the event stays queued")
        let returned = await waitUntilAsync { fx.activity.returned == 1 }
        XCTAssertTrue(returned, "the activity block returned")
    }

    func testAppFlushIsBoundedByDeadline() async throws {
        try configure()
        await awaitFlush()
        fx.register()
        fx.queue.add(event("t1"))
        StubURLProtocol.reset { _ in .hang }
        let sdk = try XCTUnwrap(SDK.current)

        let start = Date()
        let r = await sdk.startFlush(timeout: 0.3).value
        XCTAssertEqual(r, .retryLater)
        XCTAssertLessThan(Date().timeIntervalSince(start), 3)
        let held = fx.flushLock.tryLock()
        XCTAssertNotNil(held)
        held?.release()
        XCTAssertEqual(fx.queue.count, 1)
    }

    func testAppFlushDeniedActivityCancelsAtOnce() async throws {
        fx.storeConfig()
        fx.register()
        fx.queue.add(event("t1"))
        StubURLProtocol.reset { _ in .hang }
        fx.activity.deny = true

        let start = Date()
        try configure()
        let r = await awaitFlush()
        XCTAssertEqual(r, .retryLater)
        XCTAssertLessThan(Date().timeIntervalSince(start), 2, "a refused activity cancels the flush at once")
        let held = fx.flushLock.tryLock()
        XCTAssertNotNil(held)
        held?.release()
        XCTAssertEqual(fx.queue.count, 1)
    }
}
