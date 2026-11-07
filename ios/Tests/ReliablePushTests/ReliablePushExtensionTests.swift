import XCTest
import UserNotifications
@testable import ReliablePush

/// Records every content-handler call.
final class HandlerRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var list: [UNNotificationContent] = []
    private var order: [String] = []
    let called: XCTestExpectation

    init(_ called: XCTestExpectation) { self.called = called }

    var contents: [UNNotificationContent] { lock.lock(); defer { lock.unlock() }; return list }
    var count: Int { contents.count }
    var steps: [String] { lock.lock(); defer { lock.unlock() }; return order }

    func note(_ step: String) { lock.lock(); order.append(step); lock.unlock() }

    var handler: @Sendable (UNNotificationContent) -> Void {
        { content in
            self.lock.lock()
            self.list.append(content)
            self.order.append("handler")
            self.lock.unlock()
            self.called.fulfill()
        }
    }
}

final class ReliablePushExtensionTests: XCTestCase {
    private var fx: SDKFixture!

    override func setUp() {
        SDK.resetForTesting()
        RefreshGate.resetForTesting()
        StubURLProtocol.reset()
        fx = SDKFixture()
        fx.storeConfig()
        fx.register()
    }

    override func tearDown() {
        StubURLProtocol.reset()
        fx.cleanup()
    }

    private func makeExtension(timeout: TimeInterval = 5) -> ReliablePushExtension {
        ReliablePushExtension(environment: fx.environment, options: ReliablePushOptions(extensionSendTimeout: timeout))
    }

    private func request(_ userInfo: [AnyHashable: Any], title: String = "Original title") -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = "Original body"
        content.userInfo = userInfo
        return UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
    }

    private func recorder(inverted: Bool = false) -> HandlerRecorder {
        let e = expectation(description: "content handler")
        e.isInverted = inverted
        e.assertForOverFulfill = true
        return HandlerRecorder(e)
    }

    /// Waits until the flush started by the extension has ended (its lock is free again).
    private func waitForFlushEnd() {
        XCTAssertTrue(waitUntil { () -> Bool in
            guard let held = fx.flushLock.tryLock() else { return false }
            held.release()
            return true
        })
    }

    // MARK: not ours

    func testNotOursReturnsFalseAndDoesNotCallHandler() {
        let h = recorder(inverted: true)
        XCTAssertFalse(makeExtension().didReceive(request(["aps": ["alert": "x"]]), withContentHandler: h.handler))
        fx.state.config = nil
        XCTAssertFalse(makeExtension().didReceive(request([:]), withContentHandler: h.handler))
        wait(for: [h.called], timeout: 0.3)
        XCTAssertEqual(h.count, 0)
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
    }

    func testNotOursAndMalformedNeverReadTheStoredConfiguration() {
        let reads = Locked(0)
        let env = fx.environment
        let resolve: @Sendable () -> SDKEnvironment? = {
            reads.withLock { $0 += 1 }
            return env
        }
        let h = recorder(inverted: true)
        XCTAssertFalse(ReliablePushExtension(resolve: resolve, options: .init())
            .didReceive(request(["aps": ["alert": "x"]]), withContentHandler: h.handler))
        XCTAssertFalse(ReliablePushExtension(resolve: resolve, options: .init())
            .didReceive(request(["rp_tid": "not-a-uuid"]), withContentHandler: h.handler))
        wait(for: [h.called], timeout: 0.3)
        XCTAssertEqual(reads.withLock { $0 }, 0)
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
    }

    func testOursReadsTheStoredConfigurationOnceAndUnavailableGroupPassesThrough() {
        let reads = Locked(0)
        let resolve: @Sendable () -> SDKEnvironment? = {
            reads.withLock { $0 += 1 }
            return nil // App Group unavailable
        }
        let h = recorder()
        XCTAssertTrue(ReliablePushExtension(resolve: resolve, options: .init())
            .didReceive(request(["rp_tid": UUID().uuidString]), withContentHandler: h.handler))
        wait(for: [h.called], timeout: 5)
        XCTAssertEqual(reads.withLock { $0 }, 1)
        XCTAssertEqual(h.contents.first?.title, "Original title")
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertTrue(fx.warned("tracking.not_registered"))
    }

    func testConfigurationStoredAfterTheExtensionIsCreatedIsUsed() {
        fx.state.config = nil
        let ext = makeExtension()
        fx.storeConfig()
        StubURLProtocol.reset { SDKFixture.acceptAll($0) }
        let tid = UUID().uuidString
        let h = recorder()
        XCTAssertTrue(ext.didReceive(request(["rp_tid": tid]), withContentHandler: h.handler))
        wait(for: [h.called], timeout: 5)
        XCTAssertFalse(fx.warned("tracking.not_registered"))
        XCTAssertEqual(SDKFixture.sentEvents(), ["\(tid)|DELIVERED"])
        waitForFlushEnd()
    }

    func testMalformedRpTidReturnsFalseWithWarn() {
        let h = recorder(inverted: true)
        let ext = makeExtension()
        XCTAssertFalse(ext.didReceive(request(["rp_tid": "not-a-uuid"]), withContentHandler: h.handler))
        XCTAssertFalse(ext.didReceive(request(["rp_tid": 42]), withContentHandler: h.handler))
        wait(for: [h.called], timeout: 0.3)
        XCTAssertEqual(h.count, 0)
        XCTAssertTrue(fx.warned("tracking.event_dropped"))
        XCTAssertTrue(fx.log.messages.contains { $0.contains("reason=malformed_rp_tid") })
        XCTAssertEqual(fx.queue.count, 0)
    }

    // MARK: delivered

    func testDeliveredIsPersistedBeforeHandlerAndSent() {
        let tid = UUID().uuidString
        let queue = fx.queue
        let queuedAtSend = Locked<[String]>([])
        StubURLProtocol.reset { req in
            queuedAtSend.withLock { $0 = queue.peek(max: 50).map(\.key) }
            return SDKFixture.acceptAll(req)
        }
        let h = recorder()
        XCTAssertTrue(makeExtension().didReceive(request(["rp_tid": tid]), withContentHandler: { content in
            h.note("sent=\(SDKFixture.sentEvents().count)")
            h.handler(content)
        }))
        wait(for: [h.called], timeout: 5)

        XCTAssertEqual(queuedAtSend.withLock { $0 }, ["\(tid)|DELIVERED"], "persisted before the network call")
        XCTAssertEqual(SDKFixture.sentEvents(), ["\(tid)|DELIVERED"])
        XCTAssertEqual(h.steps, ["sent=1", "handler"])
        XCTAssertEqual(h.count, 1)
        XCTAssertEqual(h.contents.first?.title, "Original title")
        waitForFlushEnd()
        XCTAssertEqual(fx.queue.count, 0)
    }

    func testDeniedSettingsMeanNoDelivered() {
        for status in [UNAuthorizationStatus.denied, .notDetermined] {
            fx.settings.set(GateSettings(authorized: GateSettings.allows(status), alert: true, notificationCenter: true, lockScreen: true))
            let h = recorder()
            XCTAssertTrue(makeExtension().didReceive(request(["rp_tid": UUID().uuidString]), withContentHandler: h.handler))
            wait(for: [h.called], timeout: 5)
            XCTAssertEqual(h.count, 1)
            XCTAssertEqual(fx.queue.count, 0, "\(status)")
        }
        waitForFlushEnd()
        XCTAssertTrue(SDKFixture.sentEvents().isEmpty)
    }

    func testAllPresentationsOffMeanNoDelivered() {
        fx.settings.set(GateSettings(authorized: GateSettings.allows(.authorized), alert: false, notificationCenter: false,
                                     lockScreen: false))
        let h = recorder()
        XCTAssertTrue(makeExtension().didReceive(request(["rp_tid": UUID().uuidString]), withContentHandler: h.handler))
        wait(for: [h.called], timeout: 5)
        XCTAssertEqual(h.count, 1)
        XCTAssertEqual(fx.queue.count, 0)
        waitForFlushEnd()
        XCTAssertTrue(SDKFixture.sentEvents().isEmpty)
    }

    func testProvisionalCountsAsShown() {
        StubURLProtocol.reset { SDKFixture.acceptAll($0) }
        var expected: [String] = []
        for status in [UNAuthorizationStatus.provisional, .ephemeral] {
            let tid = UUID().uuidString
            expected.append("\(tid)|DELIVERED")
            fx.settings.set(GateSettings(authorized: GateSettings.allows(status), alert: false, notificationCenter: true,
                                         lockScreen: false))
            let h = recorder()
            XCTAssertTrue(makeExtension().didReceive(request(["rp_tid": tid]), withContentHandler: h.handler))
            wait(for: [h.called], timeout: 5)
            waitForFlushEnd()
        }
        XCTAssertEqual(SDKFixture.sentEvents(), expected)
    }

    func testZeroSendTimeoutStillReadsGateAndQueuesDelivered() {
        StubURLProtocol.reset { _ in .hang }
        let tid = UUID().uuidString
        let h = recorder()
        XCTAssertTrue(makeExtension(timeout: 0).didReceive(request(["rp_tid": tid]), withContentHandler: h.handler))
        XCTAssertEqual(fx.queue.peek(max: 10).map(\.key), ["\(tid)|DELIVERED"])
        wait(for: [h.called], timeout: 2)
        RunLoop.current.run(until: Date().addingTimeInterval(0.8))
        XCTAssertEqual(h.count, 1)
        waitForFlushEnd()
    }

    func testNotRegisteredCallsHandlerWithoutEvent() {
        fx.tokens().clear()
        let h = recorder()
        XCTAssertTrue(makeExtension().didReceive(request(["rp_tid": UUID().uuidString]), withContentHandler: h.handler))
        wait(for: [h.called], timeout: 5)
        XCTAssertEqual(h.count, 1)
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertTrue(fx.warned("tracking.not_registered"))
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
    }

    func testWithoutStoredConfigPassesThroughUntouched() {
        fx.state.config = nil
        let h = recorder()
        let customized = Locked(false)
        XCTAssertTrue(makeExtension().didReceive(request(["rp_tid": UUID().uuidString]), withContentHandler: h.handler,
                                                 customize: { content, _ in
                                                     customized.withLock { $0 = true }
                                                     content.title = "changed"
                                                 }))
        wait(for: [h.called], timeout: 5)
        XCTAssertEqual(h.contents.first?.title, "Original title")
        XCTAssertFalse(customized.withLock { $0 })
        XCTAssertEqual(fx.queue.count, 0)
        XCTAssertTrue(fx.warned("tracking.not_registered"))
    }

    // MARK: exactly once

    func testContentHandlerCalledOnceWhenBackendHangs() {
        StubURLProtocol.reset { _ in .hang }
        let tid = UUID().uuidString
        let h = recorder()
        let start = Date()
        XCTAssertTrue(makeExtension(timeout: 0.5).didReceive(request(["rp_tid": tid]), withContentHandler: h.handler))
        XCTAssertEqual(fx.queue.peek(max: 10).map(\.key), ["\(tid)|DELIVERED"], "persisted before didReceive returns")
        XCTAssertEqual(h.count, 0)
        wait(for: [h.called], timeout: 3)
        XCTAssertLessThan(Date().timeIntervalSince(start), 2)
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        XCTAssertEqual(h.count, 1)
        XCTAssertEqual(h.contents.first?.title, "Original title")
        waitForFlushEnd()
        XCTAssertEqual(fx.queue.count, 1, "the event stays queued")
    }

    func testContentHandlerCalledOnceWhenTimeExpiresDuringSend() {
        // the backend answers 0.5 s later, after the expiry
        StubURLProtocol.reset { req in
            Thread.sleep(forTimeInterval: 0.5)
            return SDKFixture.acceptAll(req)
        }
        let tid = UUID().uuidString
        let h = recorder()
        let ext = makeExtension(timeout: 10)
        XCTAssertTrue(ext.didReceive(request(["rp_tid": tid]), withContentHandler: h.handler,
                                     customize: { content, _ in content.title = "Custom" }))
        XCTAssertTrue(waitUntil { !StubURLProtocol.requests.isEmpty })
        XCTAssertEqual(h.count, 0)

        ext.serviceExtensionTimeWillExpire()
        XCTAssertEqual(h.count, 1, "called synchronously on expiry")
        XCTAssertEqual(h.contents.first?.title, "Custom", "best content: the customised one")
        XCTAssertTrue(fx.warned("extension.time_expired"))
        XCTAssertEqual(fx.queue.peek(max: 10).map(\.key), ["\(tid)|DELIVERED"])

        ext.serviceExtensionTimeWillExpire()
        wait(for: [h.called], timeout: 1)
        waitForFlushEnd()
        RunLoop.current.run(until: Date().addingTimeInterval(0.7))
        XCTAssertEqual(h.count, 1)
        XCTAssertEqual(fx.log.messages.filter { $0.contains("extension.time_expired") }.count, 1)
    }

    func testCustomizerRunsBeforeHandler() {
        StubURLProtocol.reset { SDKFixture.acceptAll($0) }
        let tid = UUID().uuidString
        let h = recorder()
        let seenTid = Locked<String?>(nil)
        XCTAssertTrue(makeExtension().didReceive(request(["rp_tid": tid, "extra": "v"]), withContentHandler: h.handler,
                                                 customize: { content, trackingId in
                                                     h.note("customize")
                                                     seenTid.withLock { $0 = trackingId }
                                                     content.title = "Hello"
                                                     content.subtitle = "Sub"
                                                 }))
        wait(for: [h.called], timeout: 5)
        XCTAssertEqual(h.steps, ["customize", "handler"])
        XCTAssertEqual(seenTid.withLock { $0 }, tid)
        let content = h.contents.first
        XCTAssertEqual(content?.title, "Hello")
        XCTAssertEqual(content?.subtitle, "Sub")
        XCTAssertEqual(content?.body, "Original body")
        XCTAssertEqual(content?.userInfo["extra"] as? String, "v")
        waitForFlushEnd()
    }
}
