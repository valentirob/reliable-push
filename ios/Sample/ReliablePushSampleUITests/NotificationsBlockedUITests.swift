import XCTest

/// Phase "blocked" of `scripts/ios-e2e.sh`: the app is freshly installed and the test answers the notification prompt
/// with "Don't Allow". The extension path then sees `authorizationStatus == .denied`, so no `DELIVERED` is reported
/// and the backend's fallback fires (Review Focus 2). It runs alone, before phase "main" reinstalls the app.
@MainActor
final class NotificationsBlockedUITests: XCTestCase {
    func testNotificationsBlockedMeansFallback() async throws {
        let sample = SampleUnderTest()
        sample.launch(reset: true)
        let auth = try await sample.settleNotificationPrompt(allow: false)
        XCTAssertEqual(auth, "denied", "notifications denied (this phase taps Don't Allow on a fresh install)")
        try await sample.register()

        let tid = try await sample.harness.push(appId: sample.appId, userId: sample.userId, title: "Blocked",
                                                body: "iOS E2E blocked", policy: "NOT_DELIVERED", timeoutMinutes: 5)
        try await sample.debugPush(tid, title: "Blocked")
        // still registered: no DELIVERED because the notification is not shown, not because the device is unknown
        XCTAssertEqual(sample.shownDeviceId, sample.deviceId, "registered when the push arrived")
        try await sample.awaitState(tid, "FALLBACK_TRIGGERED", 30)
        try await sample.awaitWebhooks(tid, 1)
        let delivered = try await sample.harness.timelineHas(tid, "DELIVERED")
        XCTAssertFalse(delivered, "no DELIVERED in the timeline")
    }
}
