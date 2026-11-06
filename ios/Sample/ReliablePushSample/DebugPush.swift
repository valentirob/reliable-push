#if DEBUG
import Foundation
import ReliablePush
import Security
import UserNotifications

/// Debug build only: `rpsample://debug-push?rp_tid=<uuid>&title=<text>&body=<text>[&delay=<seconds>]` (the harness
/// console's push preview prints the `xcrun simctl openurl` line). The Simulator runs no Notification Service
/// Extension for simulated pushes, so this builds the request an APNs push would give the extension (`aps` + top-level
/// `rp_tid`) and runs the same `ReliablePushExtension.didReceive` in the app process. The content the handler gets is
/// then added as a local notification (identifier = `rp_tid`), which the UI tests tap. `delay` (1...30 s, optional)
/// shows it that much later, so a test can send the app to the background first and tap the banner on Springboard.
enum DebugPush {
    /// `didReceive` blocks its thread briefly (settings check, queue write): never the main thread, never the
    /// cooperative pool.
    private static let queue = DispatchQueue(label: "io.reliablepush.sample.debug-push")

    @MainActor
    static func handle(_ url: URL, model: SampleModel) {
        guard url.scheme == "rpsample", url.host == "debug-push",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return }
        func value(_ name: String) -> String { items.first { $0.name == name }?.value ?? "" }
        let tid = value("rp_tid")
        let title = value("title")
        let body = value("body")
        let delay = min(max(Double(value("delay")) ?? 0, 0), 30)
        guard !tid.isEmpty else {
            sampleLog.error("event=sample.debug_push_ignored reason=no_rp_tid")
            return
        }
        let appGroup = model.config.appGroup
        model.lastPush = "received \(tid)"
        queue.async {
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            content.userInfo = ["aps": ["alert": ["title": title, "body": body], "mutable-content": 1], "rp_tid": tid]
            let request = UNNotificationRequest(identifier: tid, content: content, trigger: nil)
            let handler: (UNNotificationContent) -> Void = { shown in show(shown, id: tid, delay: delay, model: model) }
            let reliablePush = ReliablePushExtension(appGroup: appGroup)
            if !reliablePush.didReceive(request, withContentHandler: handler) {
                handler(request.content) // not the SDK's: shown as is
            }
        }
    }

    private static func show(_ content: UNNotificationContent, id: String, delay: TimeInterval, model: SampleModel) {
        let trigger = delay > 0 ? UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false) : nil
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: trigger)) { error in
            // not allowed (notifications denied) is expected in the blocked test; the push is handled either way
            if let error {
                sampleLog.info("event=sample.debug_push_not_shown rp_tid=\(id, privacy: .public) error=\(error.localizedDescription, privacy: .public)")
            }
            Task { @MainActor in model.lastPush = "handled \(id)" }
        }
    }
}

/// Debug build only: `RP_RESET=1` (the UI tests) starts the SDK from nothing, as a fresh install would: before
/// `configure`, it deletes the SDK's Keychain item(s) in the App Group access group, its App Group defaults
/// (`io.reliablepush.*`), its queue directory, and the sample's notifications.
enum DebugReset {
    static func run(appGroup: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "io.reliablepush.devicetoken",
            kSecAttrAccessGroup as String: appGroup,
        ]
        let status = SecItemDelete(query as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            sampleLog.error("event=sample.reset_keychain_failed status=\(status, privacy: .public)")
        }
        if let defaults = UserDefaults(suiteName: appGroup) {
            for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("io.reliablepush.") {
                defaults.removeObject(forKey: key)
            }
        }
        if let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
            try? FileManager.default.removeItem(at: container.appendingPathComponent("Library/Application Support/ReliablePush"))
        }
        let center = UNUserNotificationCenter.current()
        center.removeAllDeliveredNotifications()
        center.removeAllPendingNotificationRequests()
        sampleLog.info("event=sample.reset")
    }
}
#endif
