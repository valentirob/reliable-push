import XCTest
import UserNotifications
@testable import ReliablePush

final class ReliablePushTests: XCTestCase {
    private var fx: SDKFixture!

    override func setUp() {
        SDK.resetForTesting()
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

    func testRestoredBackupWithoutKeychainRecordIsNotRegisteredAfterConfigure() async throws {
        // the App Group defaults (config, device id) came back from a backup, the ThisDeviceOnly Keychain record did not
        fx.storeConfig()
        fx.state.deviceId = "dev1"
        try configure()
        await awaitFlush()
        XCTAssertNil(ReliablePush.deviceId, "not registered after a restore")
        XCTAssertNil(fx.state.deviceId, "the stale cache is dropped")
    }

    func testDeviceIdFollowsTheKeychainRecordNotTheCache() async throws {
        try configure()
        await awaitFlush()
        fx.register(deviceId: "dev1")
        fx.state.deviceId = "stale" // a SharedState cache differing from the Keychain record loses
        XCTAssertEqual(ReliablePush.deviceId, "dev1")
        fx.secrets.force(.unavailable) // Keychain locked: the cache is the best answer
        XCTAssertEqual(ReliablePush.deviceId, "stale")
        fx.secrets.force(nil)
    }

    func testFirstConfigureWithLockedKeychainKeepsRecordAndPersistsNothing() async throws {
        // a background launch before the first unlock after a reboot: the App Group defaults read as empty and the
        // Keychain is locked; that is not a fresh install
        fx.register()
        fx.state.clearRegistration()
        fx.secrets.force(.unavailable)
        try configure()
        await awaitFlush()
        XCTAssertNil(fx.state.config, "nothing persisted while the storage is locked")
        fx.secrets.force(nil)
        XCTAssertTrue(fx.secrets.has("app1"), "the registration survives")
        XCTAssertTrue(fx.log.messages.contains { $0.contains("event=sdk.reconfigured") && $0.contains("reason=storage_locked") })
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

    func testLogoutDuringEnsureRegisteredRefreshLeavesNoRegistration() async throws {
        try configure()
        await awaitFlush()
        fx.register(token: SDKFixture.staleToken)
        StubURLProtocol.reset { req in
            switch req.url?.path {
            case "/v1/devices/token:refresh":
                Thread.sleep(forTimeInterval: 0.5) // the new token arrives after the logout
                return SDKFixture.refreshed()
            case "/v1/devices/me": return .response(status: 204)
            default: return SDKFixture.acceptAll(req)
            }
        }
        let ensured = Task { await ReliablePush.ensureRegistered(idTokenProvider: { nil }) }
        let refreshing = await waitUntilAsync { StubURLProtocol.requests.contains { $0.url.path == "/v1/devices/token:refresh" } }
        XCTAssertTrue(refreshing)

        let confirmed = await withCheckedContinuation { c in ReliablePush.logout { c.resume(returning: $0) } }
        XCTAssertTrue(confirmed)
        let result = await ensured.value
        XCTAssertEqual(result, .failed(httpStatus: nil), "the refreshed token is not stored after a logout")
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertFalse(fx.secrets.has("app1"), "no token saved after logout")
        XCTAssertNil(ReliablePush.deviceId)
    }

    // MARK: ensureRegistered (spec 3.2)

    /// A provider that counts its calls and answers `token` (nil: not logged in), optionally after `delay` seconds.
    private func provider(_ token: String?, delay: TimeInterval = 0, calls: Locked<Int>) -> @Sendable () async -> String? {
        {
            calls.withLock { $0 += 1 }
            if delay > 0 { try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
            return token
        }
    }

    private var paths: [String] { StubURLProtocol.requests.map(\.url.path) }

    func testEnsureRegisteredWithoutTokenAsksTheProviderRegistersAndFlushes() async throws {
        try configure()
        await awaitFlush()
        fx.queue.add(event("t1", .read))
        StubURLProtocol.reset { req in req.url?.path == "/v1/devices" ? SDKFixture.registration() : SDKFixture.acceptAll(req) }
        let calls = Locked(0)

        let result = await ReliablePush.ensureRegistered(idTokenProvider: provider("id-token-1", calls: calls))
        XCTAssertEqual(result, .success(deviceId: "dev1"))
        XCTAssertEqual(calls.withLock { $0 }, 1)
        guard case let .registered(deviceId, token) = fx.tokens().load() else { return XCTFail("not stored") }
        XCTAssertEqual(deviceId, "dev1")
        XCTAssertEqual(token.raw, SDKFixture.goodToken)
        let body = try XCTUnwrap(StubURLProtocol.requests.first { $0.url.path == "/v1/devices" }?.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["idToken"] as? String, "id-token-1")
        XCTAssertNil(json["deviceId"])

        let r = await awaitFlush()
        XCTAssertEqual(r, .done)
        XCTAssertEqual(paths, ["/v1/devices", "/v1/events"])
        XCTAssertEqual(fx.queue.count, 0)
    }

    func testEnsureRegisteredProviderNilIsNotLoggedInWithoutAnyCall() async throws {
        try configure()
        await awaitFlush()
        let calls = Locked(0)
        let result = await ReliablePush.ensureRegistered(idTokenProvider: provider(nil, calls: calls))
        XCTAssertEqual(result, .notLoggedIn)
        XCTAssertEqual(calls.withLock { $0 }, 1)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
        XCTAssertEqual(fx.activity.performed, 1, "no flush beyond the launch one")
    }

    func testEnsureRegisteredValidTokenIsAlreadyRegisteredWithoutNetwork() async throws {
        try configure()
        await awaitFlush()
        fx.register()
        let calls = Locked(0)
        let result = await ReliablePush.ensureRegistered(idTokenProvider: provider("id-token-1", calls: calls))
        XCTAssertEqual(result, .alreadyRegistered)
        XCTAssertEqual(calls.withLock { $0 }, 0, "the provider is not asked")
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
    }

    func testEnsureRegisteredInTheLastTwentyPercentRefreshes() async throws {
        try configure()
        await awaitFlush()
        fx.register(token: SDKFixture.staleToken)
        StubURLProtocol.reset { req in
            req.url?.path == "/v1/devices/token:refresh" ? SDKFixture.refreshed() : SDKFixture.acceptAll(req)
        }
        let calls = Locked(0)
        let result = await ReliablePush.ensureRegistered(idTokenProvider: provider("id-token-1", calls: calls))
        XCTAssertEqual(result, .success(deviceId: "dev1"))
        XCTAssertEqual(calls.withLock { $0 }, 0)
        XCTAssertEqual(StubURLProtocol.requests.first?.url.path, "/v1/devices/token:refresh")
        XCTAssertEqual(StubURLProtocol.requests.first?.headers["Authorization"], "Bearer \(SDKFixture.staleToken)")
        guard case let .registered(deviceId, token) = fx.tokens().load() else { return XCTFail("not stored") }
        XCTAssertEqual(deviceId, "dev1")
        XCTAssertEqual(token.raw, SDKFixture.freshToken)
        XCTAssertTrue(fx.log.messages.contains { $0.contains("event=token.refreshed") })
    }

    func testEnsureRegisteredWithRenewalNeededRefreshesClearsTheFlagAndFlushes() async throws {
        try configure()
        await awaitFlush()
        fx.register()
        fx.tokens().markRenewalNeeded() // a 401 seen by the extension
        fx.queue.add(event("t1"))
        StubURLProtocol.reset { req in
            req.url?.path == "/v1/devices/token:refresh" ? SDKFixture.refreshed() : SDKFixture.acceptAll(req)
        }
        let result = await ReliablePush.ensureRegistered(idTokenProvider: { nil })
        XCTAssertEqual(result, .success(deviceId: "dev1"))
        XCTAssertFalse(fx.tokens().renewalNeeded)
        XCTAssertFalse(fx.state.renewalNeeded)
        let r = await awaitFlush()
        XCTAssertEqual(r, .done)
        XCTAssertEqual(paths, ["/v1/devices/token:refresh", "/v1/events"])
        XCTAssertEqual(StubURLProtocol.requests.last?.headers["Authorization"], "Bearer \(SDKFixture.freshToken)")
        XCTAssertEqual(fx.queue.count, 0)
    }

    func testEnsureRegisteredRefreshAnswered401TakesTheIdTokenPath() async throws {
        try configure()
        await awaitFlush()
        fx.register(token: SDKFixture.staleToken)
        StubURLProtocol.reset { req in
            switch req.url?.path {
            case "/v1/devices/token:refresh": return .response(status: 401)
            case "/v1/devices": return SDKFixture.registration(token: SDKFixture.freshToken)
            default: return SDKFixture.acceptAll(req)
            }
        }
        let calls = Locked(0)
        let result = await ReliablePush.ensureRegistered(idTokenProvider: provider("id-token-1", calls: calls))
        XCTAssertEqual(result, .success(deviceId: "dev1"))
        XCTAssertEqual(calls.withLock { $0 }, 1)
        XCTAssertEqual(Array(paths.prefix(2)), ["/v1/devices/token:refresh", "/v1/devices"])
        let body = try XCTUnwrap(StubURLProtocol.requests.first { $0.url.path == "/v1/devices" }?.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["deviceId"] as? String, "dev1", "the known device id is kept")
        guard case let .registered(_, token) = fx.tokens().load() else { return XCTFail("not stored") }
        XCTAssertEqual(token.raw, SDKFixture.freshToken)

        // the same with nobody logged in: notLoggedIn, nothing registered
        fx.register(token: SDKFixture.staleToken)
        StubURLProtocol.reset { _ in .response(status: 401) }
        let none = await ReliablePush.ensureRegistered(idTokenProvider: { nil })
        XCTAssertEqual(none, .notLoggedIn)
        XCTAssertEqual(paths, ["/v1/devices/token:refresh"])
    }

    func testEnsureRegisteredWithExpiredTokenTakesTheIdTokenPath() async throws {
        try configure()
        await awaitFlush()
        fx.register(token: SDKFixture.expiredToken)
        StubURLProtocol.reset { req in req.url?.path == "/v1/devices" ? SDKFixture.registration() : SDKFixture.acceptAll(req) }
        let calls = Locked(0)
        let result = await ReliablePush.ensureRegistered(idTokenProvider: provider("id-token-1", calls: calls))
        XCTAssertEqual(result, .success(deviceId: "dev1"))
        XCTAssertEqual(calls.withLock { $0 }, 1)
        XCTAssertFalse(paths.contains("/v1/devices/token:refresh"), "no refresh with an expired token")
        XCTAssertEqual(paths.first, "/v1/devices")
    }

    func testConcurrentEnsureRegisteredCallsJoinOneRun() async throws {
        try configure()
        await awaitFlush()
        StubURLProtocol.reset { req in req.url?.path == "/v1/devices" ? SDKFixture.registration() : SDKFixture.acceptAll(req) }
        let calls = Locked(0)
        let slow = provider("id-token-1", delay: 0.3, calls: calls)
        async let first = ReliablePush.ensureRegistered(idTokenProvider: slow)
        async let second = ReliablePush.ensureRegistered(idTokenProvider: slow)
        let results = await [first, second]
        XCTAssertEqual(results, [.success(deviceId: "dev1"), .success(deviceId: "dev1")])
        XCTAssertEqual(calls.withLock { $0 }, 1, "one provider call")
        XCTAssertEqual(paths.filter { $0 == "/v1/devices" }.count, 1, "one network sequence")

        // the run is over: the next call decides again (now registered)
        let again = await ReliablePush.ensureRegistered(idTokenProvider: slow)
        XCTAssertEqual(again, .alreadyRegistered)
    }

    func testEnsureRegisteredNeverBlocksTheCallerAndCallsBackOnMain() async throws {
        try configure()
        await awaitFlush()
        StubURLProtocol.reset { req in req.url?.path == "/v1/devices" ? SDKFixture.registration() : SDKFixture.acceptAll(req) }
        let calls = Locked(0)
        let done = expectation(description: "completion")
        let start = Date()
        ReliablePush.ensureRegistered(idTokenProvider: provider("id-token-1", delay: 0.5, calls: calls)) { result in
            XCTAssertTrue(Thread.isMainThread)
            XCTAssertEqual(result, .success(deviceId: "dev1"))
            done.fulfill()
        }
        XCTAssertLessThan(Date().timeIntervalSince(start), 0.1, "returns at once")
        await fulfillment(of: [done], timeout: 5)
    }

    func testEnsureRegisteredFailuresMapToResults() async throws {
        let unconfigured = await ReliablePush.ensureRegistered(idTokenProvider: { "id-token-1" })
        XCTAssertEqual(unconfigured, .failed(httpStatus: nil), "not configured")

        try configure()
        await awaitFlush()
        fx.register(token: SDKFixture.staleToken)
        StubURLProtocol.reset { _ in .response(status: 503) }
        var r = await ReliablePush.ensureRegistered(idTokenProvider: { "id-token-1" })
        XCTAssertEqual(r, .failed(httpStatus: 503))
        StubURLProtocol.reset { _ in .fail }
        r = await ReliablePush.ensureRegistered(idTokenProvider: { "id-token-1" })
        XCTAssertEqual(r, .failed(httpStatus: nil))
        guard case let .registered(_, token) = fx.tokens().load() else { return XCTFail("registration kept") }
        XCTAssertEqual(token.raw, SDKFixture.staleToken)

        // Keychain unreadable: no decision, no provider call, no network
        StubURLProtocol.reset { _ in .response(status: 500) }
        fx.secrets.force(.unavailable)
        let calls = Locked(0)
        r = await ReliablePush.ensureRegistered(idTokenProvider: provider("id-token-1", calls: calls))
        fx.secrets.force(nil)
        XCTAssertEqual(r, .failed(httpStatus: nil))
        XCTAssertEqual(calls.withLock { $0 }, 0)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)

        // ID token rejected
        fx.tokens().clear()
        StubURLProtocol.reset { _ in .response(status: 401) }
        r = await ReliablePush.ensureRegistered(idTokenProvider: { "bad" })
        XCTAssertEqual(r, .failed(httpStatus: 401))
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

    func testLogoutCancelsEveryInFlightAppFlush() async throws {
        fx.storeConfig()
        fx.register()
        fx.queue.add(event("t1"))
        StubURLProtocol.reset { req in req.url?.path == "/v1/events" ? .hang : .response(status: 204) }
        try configure() // the launch flush takes flush.lock and hangs in the send
        let inFlight = await waitUntilAsync { StubURLProtocol.requests.contains { $0.url.path == "/v1/events" } }
        XCTAssertTrue(inFlight)
        let sdk = try XCTUnwrap(SDK.current)
        let first = try XCTUnwrap(sdk.lastFlushForTesting)
        let second = sdk.startFlush() // a later flush waits for the lock the first one holds (M7)
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(StubURLProtocol.requests.filter { $0.url.path == "/v1/events" }.count, 1, "the second is waiting")

        let start = Date()
        let confirmed = await withCheckedContinuation { c in ReliablePush.logout { c.resume(returning: $0) } }
        XCTAssertTrue(confirmed)
        XCTAssertLessThan(Date().timeIntervalSince(start), 1.5, "the lock holder was cancelled, no 2 s lock wait")
        XCTAssertFalse(fx.log.messages.contains { $0.contains("event=lock.unavailable") }, "\(fx.log.messages)")
        let r = await first.value
        XCTAssertEqual(r, .retryLater)
        let waiting = await second.value
        XCTAssertEqual(waiting, .retryLater, "cancelled while waiting: never took the lock, events kept")
        XCTAssertEqual(StubURLProtocol.requests.filter { $0.url.path == "/v1/events" }.count, 1)
        XCTAssertFalse(fx.secrets.has("app1"))
        XCTAssertEqual(fx.queue.count, 0)
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
