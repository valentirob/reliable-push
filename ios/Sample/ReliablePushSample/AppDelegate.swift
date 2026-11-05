import os
import ReliablePush
import UIKit
import UserNotifications

/// Logger of the sample app. Never logs tokens, ID tokens, grants or notification content.
let sampleLog = Logger(subsystem: "io.reliablepush.sample", category: "Sample")

/// The sample's settings: the Info.plist values set by `Config/*.xcconfig`. In Debug builds the launch environment
/// overrides them (the UI tests pass `RP_BASE_URL`, `RP_APP_ID`, `RP_HARNESS_URL`, `RP_USER_ID`, `RP_RESET`).
struct SampleConfig: Sendable {
    let baseURL: String
    let appId: String
    let appGroup: String
    /// Debug only: the harness console that plays the customer's backend (grants).
    let harnessURL: String
    /// Debug only: the user the harness grant is minted for.
    let userId: String
    /// Debug only: `RP_RESET=1` clears the SDK's Keychain item, App Group state and queue before `configure`.
    let reset: Bool
    /// Debug only: `RP_APNS=1` (a real APNs run, see docs/testing.md): register for remote notifications and report the
    /// APNs device token to the harness.
    let apns: Bool

    static func load(bundle: Bundle = .main,
                     environment: [String: String] = ProcessInfo.processInfo.environment) -> SampleConfig {
        func info(_ key: String) -> String { bundle.object(forInfoDictionaryKey: key) as? String ?? "" }
        #if DEBUG
        func env(_ key: String, _ fallback: String) -> String {
            let value = environment[key] ?? ""
            return value.isEmpty ? fallback : value
        }
        return SampleConfig(baseURL: env("RP_BASE_URL", info("RPBaseURL")), appId: env("RP_APP_ID", info("RPAppId")),
                            appGroup: info("RPAppGroup"), harnessURL: env("RP_HARNESS_URL", info("RPHarnessURL")),
                            userId: env("RP_USER_ID", "ios-sample-user"), reset: environment["RP_RESET"] == "1",
                            apns: info("RPApns") == "1")
        #else
        return SampleConfig(baseURL: info("RPBaseURL"), appId: info("RPAppId"), appGroup: info("RPAppGroup"),
                            harnessURL: "", userId: "", reset: false, apns: false)
        #endif
    }
}

/// Configures the SDK once per launch and reports taps (READ). Notifications that arrive in the foreground are shown
/// as banners and in Notification Center.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    let config = SampleConfig.load()
    lazy var model = SampleModel(config: config)

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        #if DEBUG
        if config.reset { DebugReset.run(appGroup: config.appGroup) }
        #endif
        do {
            try ReliablePush.configure(baseURL: config.baseURL, appId: config.appId, appGroup: config.appGroup)
            model.configured()
        } catch {
            sampleLog.error("event=sample.configure_failed error=\(String(describing: error), privacy: .public)")
            model.status = "configure failed: \(error)"
        }
        UNUserNotificationCenter.current().delegate = self
        #if DEBUG
        if config.apns { application.registerForRemoteNotifications() }
        #endif
        return true
    }

    #if DEBUG
    /// RP_APNS=1 only: the hex token goes to the harness (never logged), which sends the real pushes with it.
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        guard config.apns else { return }
        model.apnsTokenReceived(deviceToken.map { String(format: "%02x", $0) }.joined())
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        sampleLog.error("event=sample.apns_registration_failed")
    }
    #endif

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        ReliablePush.trackOpen(response: response) // READ for the SDK's notifications; false for any other
        completionHandler()
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list])
    }
}
