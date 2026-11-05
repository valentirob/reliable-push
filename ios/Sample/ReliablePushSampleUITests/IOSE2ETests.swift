import XCTest

/// Simulator E2E against the local harness stack (`scripts/ios-e2e.sh`, phase "main", after Allow): the real SDK in
/// the sample app talks to the real backend through the harness's device proxy (`RP_BASE_URL`), and the harness
/// (`RP_HARNESS_URL`) sends the pushes and shows the notification state.
///
/// The Simulator runs no Notification Service Extension for simulated pushes, so each push is handed to the app as
/// `rpsample://debug-push?…`: the app runs the same `ReliablePushExtension` code in-process and adds the resulting
/// content as a local notification. Every test creates its own user and launches the app with `RP_RESET=1` (a fresh
/// process, so the one-refresh-per-process gate is reset too).
@MainActor
final class IOSE2ETests: XCTestCase {
    override func tearDown() async throws {
        // never leave the device network offline for the next test
        try? await Harness().setDeviceNetwork(online: true)
    }

    func testDeliveredIsReported() async throws {
        let sample = try await registeredSample()
        let tid = try await sample.harness.push(appId: sample.appId, userId: sample.userId, title: "Delivered",
                                                body: "iOS E2E delivered", policy: "NOT_DELIVERED", timeoutMinutes: 5)
        try await sample.debugPush(tid, title: "Delivered")
        try await sample.awaitState(tid, "DELIVERED", 5)
        try await Task.sleep(nanoseconds: 7_000_000_000) // past the 5 s (fast-clock) timeout
        let state = try await sample.harness.state(tid)
        XCTAssertEqual(state, "DELIVERED")
        let webhooks = try await sample.harness.webhookCount(tid)
        XCTAssertEqual(webhooks, 0, "no fallback webhook after the timeout")
        let inbox = try await sample.harness.inboxCalls(appId: sample.appId, tid: tid)
        XCTAssertEqual(inbox, 0, "no inbox call for \(tid)")
    }

    func testReadIsReportedOnTap() async throws {
        let sample = try await registeredSample()
        let title = "Read me \(UUID().uuidString.prefix(8))"
        let tid = try await sample.harness.push(appId: sample.appId, userId: sample.userId, title: title,
                                                body: "Tap to open", policy: "NOT_READ", timeoutMinutes: 120)
        // DELIVERED is reported at once; the notification shows 3 s later, when the app is in the background
        try await sample.debugPush(tid, title: title, delay: 3)
        XCUIDevice.shared.press(.home)
        try await sample.awaitState(tid, "DELIVERED", 20)

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let entry = springboard.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
        if !entry.waitForExistence(timeout: 10) {
            // the banner is gone already: open Notification Center (swipe down from the top edge)
            let top = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.0))
            let bottom = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.6))
            top.press(forDuration: 0.1, thenDragTo: bottom)
        }
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "notification '\(title)' on Springboard")
        // In Notification Center a tap may only expand a stack of notifications or reveal the cell's "Open" action:
        // tap the action when it shows, else the notification again.
        var opened = false
        for _ in 1...4 where !opened {
            let open = springboard.buttons["Open"]
            if open.exists { open.tap() } else if entry.exists { entry.tap() }
            opened = sample.app.wait(for: .runningForeground, timeout: 4)
        }
        XCTAssertTrue(opened, "the app opened by the tap")
        try await sample.awaitState(tid, "READ", 30)
    }

    func testOfflineDeliveryIsSentOnNextLaunch() async throws {
        let sample = try await registeredSample()
        let tid = try await sample.harness.push(appId: sample.appId, userId: sample.userId, title: "Offline",
                                                body: "iOS E2E offline", policy: "NOT_DELIVERED", timeoutMinutes: 60)
        var state = try await sample.harness.state(tid)
        XCTAssertEqual(state, "PENDING")

        try await sample.harness.setDeviceNetwork(online: false)
        try await sample.debugPush(tid, title: "Offline")
        try await Task.sleep(nanoseconds: 3_000_000_000)
        state = try await sample.harness.state(tid)
        XCTAssertEqual(state, "PENDING", "DELIVERED waits in the queue while the backend is unreachable")

        try await sample.harness.setDeviceNetwork(online: true)
        sample.app.terminate()
        sample.launch(reset: false) // configure flushes the queue once per launch
        try await sample.awaitState(tid, "DELIVERED", 45)
    }

    func testTokenRefreshNearExpiry() async throws {
        // app news: device tokens valid 20 s; the SDK refreshes when less than 20 % (4 s) is left
        let sample = try await registeredSample(appId: E2EConfig.news)
        let deviceId = try XCTUnwrap(sample.deviceId)
        // 17 s after the token was issued: 3 s of the 20 s left
        let wait = sample.registeredAt.addingTimeInterval(17).timeIntervalSinceNow
        if wait > 0 { try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }

        let tid = try await sample.harness.push(appId: sample.appId, userId: sample.userId, title: "Refresh",
                                                body: "iOS E2E refresh", policy: "NOT_DELIVERED", timeoutMinutes: 30)
        try await sample.debugPush(tid, title: "Refresh")
        try await sample.awaitState(tid, "DELIVERED", 20)
        try await awaitUntil(15, "backend log device.token_refreshed for device \(deviceId)") {
            try await sample.harness.logs("device.token_refreshed").contains { ($0["rp.device_id"] as? String) == deviceId }
        }
    }

    func testLogoutRevokesDevice() async throws {
        let sample = try await registeredSample()
        try await sample.logout()

        let tid = try await sample.harness.push(appId: sample.appId, userId: sample.userId, title: "Logged out",
                                                body: "iOS E2E logout", policy: "NOT_DELIVERED", timeoutMinutes: 5)
        try await sample.debugPush(tid, title: "Logged out")
        XCTAssertEqual(sample.shownDeviceId, "not registered", "still logged out when the push arrived")
        try await sample.awaitState(tid, "FALLBACK_TRIGGERED", 30)
        try await sample.awaitWebhooks(tid, 1)
        let delivered = try await sample.harness.timelineHas(tid, "DELIVERED")
        XCTAssertFalse(delivered, "no DELIVERED in the timeline")
    }

    // MARK: Helpers

    /// A fresh launch (`RP_RESET=1`), notifications allowed (the prompt shows once per install), a new user registered.
    private func registeredSample(appId: String = E2EConfig.shop) async throws -> SampleUnderTest {
        let sample = SampleUnderTest(appId: appId)
        sample.launch(reset: true)
        let auth = try await sample.settleNotificationPrompt(allow: true)
        XCTAssertEqual(auth, "authorized", "notifications allowed (phase main taps Allow)")
        try await sample.register()
        return sample
    }
}
