import Foundation
import UserNotifications

/// The part of the notification settings that decides whether a notification will be shown.
struct GateSettings: Sendable {
    /// `authorizationStatus` is `.authorized`, `.provisional` or `.ephemeral` (see `allows(_:)`).
    var authorized: Bool
    var alert: Bool
    var notificationCenter: Bool
    var lockScreen: Bool

    /// Shown = authorized, and at least one of alert / Notification Center / lock screen enabled (spec: DELIVERED
    /// only when the notification will be shown).
    var isShown: Bool { authorized && (alert || notificationCenter || lockScreen) }

    /// Authorized, provisional and ephemeral let a notification be shown; denied, not determined and unknown do not.
    static func allows(_ status: UNAuthorizationStatus) -> Bool {
        status == .authorized || status == .provisional || status == .ephemeral
    }
}

/// Source of the current notification settings; the tests inject a fake.
protocol NotificationSettingsSource: Sendable {
    func current() async -> GateSettings
}

/// Reads `UNUserNotificationCenter.current().notificationSettings()`.
struct SystemNotificationSettings: NotificationSettingsSource {
    func current() async -> GateSettings {
        let s = await UNUserNotificationCenter.current().notificationSettings()
        return GateSettings(authorized: GateSettings.allows(s.authorizationStatus), alert: s.alertSetting == .enabled,
                            notificationCenter: s.notificationCenterSetting == .enabled,
                            lockScreen: s.lockScreenSetting == .enabled)
    }
}

enum NotificationGate {
    /// Synchronous read of `source`, bounded by `timeout` (nil when it did not answer in time). Used by the
    /// extension so DELIVERED is on disk before `didReceive` returns. Call it only from synchronous code outside Swift's
    /// cooperative thread pool (the extension's synchronous override): it blocks the calling thread while `source` runs
    /// on the pool.
    static func settings(from source: any NotificationSettingsSource, timeout: TimeInterval) -> GateSettings? {
        let result = Locked<GateSettings?>(nil)
        let done = DispatchSemaphore(value: 0)
        let read = Task {
            let s = await source.current()
            result.withLock { $0 = s }
            done.signal()
        }
        guard done.wait(timeout: .now() + max(timeout, 0)) == .success else {
            read.cancel() // a source that honours cancellation stops; the answer is ignored either way
            return nil
        }
        return result.withLock { $0 }
    }
}
