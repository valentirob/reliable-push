# iOS SDK guide

The iOS SDK (Swift package `ReliablePush`, iOS 15.0+, Swift 6) tracks pushes that your backend sends through APNs. A
Notification Service Extension (NSE) in your app reports `DELIVERED` when the notification is about to be shown, even
when the app is not running, and the app reports `READ` when the user taps it. Events that could not be sent are
queued and retried. Apple frameworks only (Foundation, UserNotifications, Security, OSLog); the stripped arm64 library
is about 273 KB (CI gate: under 307,200 bytes).

Your backend registers each notification with reliable-push and puts the returned `trackingId` into the APNs payload as
`rp_tid`: see [integration-guide.md](integration-guide.md) §1 and §2. This page is the app side.

> **Status.** Version `0.1.0-SNAPSHOT`, built and tested locally (package tests, Simulator end-to-end suite) and in CI. There
> is no release tag yet: SwiftPM by git tag is the only distribution channel (no XCFramework, no CocoaPods).

## 1. Add the package

The manifest `Package.swift` is at the repository root, so Xcode resolves it by URL: File > Add Package Dependencies,
enter the repository URL, pick the `ReliablePush` library and add it to **both** the app target and the NSE target. Or in
a `Package.swift`:

```swift
.package(url: "https://github.com/valentirob/reliable-push.git", from: "0.1.0"),   // once a tag exists
// target dependency: .product(name: "ReliablePush", package: "reliable-push")
```

The library has no third-party dependencies and ships its own `PrivacyInfo.xcprivacy` (section 11).

## 2. App Group and Keychain

The app and the NSE are two processes. They share state through an **App Group** (for example
`group.com.example.app`): add the App Groups capability to the app target and to the NSE target with the same id.

- `UserDefaults(suiteName:)` of the group holds the configuration and the non-secret state (device id, token expiry,
  retry-not-before).
- The event queue is a file in the group container (`Library/Application Support/ReliablePush/events.json`), guarded by a
  `flock` so the app and the NSE never corrupt it.
- The device token lives in the Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: no backup, no migration, so
  a restored device starts unregistered). Its access group defaults to **the App Group id**: an App Group is a valid
  keychain access group, so no team-prefixed `keychain-access-groups` entitlement is needed. Override it with
  `ReliablePushOptions(keychainAccessGroup:)` (then pass the same options in the app and in the NSE).
- The token and the device id are saved as one Keychain record, so a reader never sees half a registration.

## 3. Configure in `didFinishLaunching`

```swift
import ReliablePush

func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
    do {
        try ReliablePush.configure(baseURL: "https://push.example.com",   // the reliable-push API
                                   appId: "shop",                         // the app id your operator configured
                                   appGroup: "group.com.example.app")
    } catch {
        // see the error cases below
    }
    UNUserNotificationCenter.current().delegate = self                    // for trackOpen (section 6)
    return true
}
```

- Call it on **every launch**, before any other SDK method. The base URL and app id are persisted in the App Group, where
  the NSE reads them.
- The same base URL and app id is a no-op (the registration is kept). A different one replaces the stored configuration
  and clears the registration, the queued events and any back-off; on that path `configure` waits at most about 2 s for
  a flush still running in the NSE.
- On a fresh install (no stored configuration) a Keychain record left by an earlier installation is cleared, so a
  reinstalled app starts unregistered and registers again after login.
- Events left in the queue by an earlier launch are sent at `configure`, once per launch.
- Redirects are never followed. Sessions are ephemeral.

`configure` throws `ReliablePushError`:

| Error | Cause |
|---|---|
| `insecureBaseURL` | `http` for a host other than `localhost`, `127.0.0.1`, `::1` |
| `invalidBaseURL` | not an absolute http(s) URL, or it has user info, a query or a fragment |
| `invalidAppId` | the app id is empty or whitespace |
| `appGroupUnavailable` | the App Group cannot be opened: check that the id is spelled the same in `configure`, `ReliablePushExtension` and the entitlements of the app and the NSE |

## 4. Register the device after login

```swift
switch await ReliablePush.register(idToken: idToken) {      // also: register(idToken:completion:), main-actor callback
case .success(let deviceId): break
case .failed(let httpStatus): break                         // nil = network error, token not stored, or not configured
}
ReliablePush.logout { confirmed in }   // revokes at the backend (best effort), clears token, registration and queue
```

`idToken` is your OIDC ID token (or a signed grant, as the harness uses); it is never logged. The SDK refreshes the
device token before it expires (when less than 20 % of its lifetime is left) and once on a `401`, at most one refresh per
process (the app and the NSE count separately). A refresh answered `401` clears the registration: call `register` again.
`ReliablePush.deviceId` is `nil` while not registered.

## 5. The Notification Service Extension

Add a Notification Service Extension target, add the same App Group to it, link `ReliablePush`, and use the helper:

```swift
import ReliablePush
import UserNotifications

final class NotificationService: UNNotificationServiceExtension {
    private let reliablePush = ReliablePushExtension(appGroup: "group.com.example.app")

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        if !reliablePush.didReceive(request, withContentHandler: contentHandler) {
            contentHandler(request.content)   // not a reliable-push notification: your own handling
        }
    }

    override func serviceExtensionTimeWillExpire() {
        reliablePush.serviceExtensionTimeWillExpire()
    }
}
```

- **Call `didReceive` from the extension's synchronous override, once per instance**, as above, never from async code:
  it reads the notification settings and writes the queue on the calling thread (briefly; the settings read is capped
  at 2 s), and the content handler it takes is the system's plain, non-`Sendable` closure.
- It returns `false`, without touching the handler, when the request has no valid `rp_tid` (a malformed one is logged
  as WARN), so your extension can handle its own pushes. Otherwise it returns `true` and calls the handler **exactly
  once**: after the send, after `ReliablePushOptions.extensionSendTimeout` (default 5 s, clamped to 0...25), or at
  `serviceExtensionTimeWillExpire`, whichever comes first.
- `DELIVERED` is recorded only when the device is registered and the notification will actually be shown (alerts
  authorized); it is persisted in the queue before `didReceive` returns, then sent in the time left.
- An optional `customize:` closure receives a mutable copy of the content and the tracking id (title, attachments...).
- `ReliablePushExtension(appGroup:options:)` takes the same `keychainAccessGroup` as the app, if you overrode it.

## 6. Track opens

```swift
extension AppDelegate: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        ReliablePush.trackOpen(response: response)   // true when it is a reliable-push notification
        completionHandler()
    }
}
```

`READ` is persisted in the queue before `trackOpen` returns, then flushed. A dismiss action reports nothing. It returns
`false` for notifications without a valid `rp_tid`, so your own handling can follow. Set the delegate in
`didFinishLaunching` so a launch by tap is seen.

## 7. Notification permission

The **host app** asks for authorization; the SDK never does:

```swift
let granted = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
```

`DELIVERED` is reported only when the notification will be shown. If the user denied notifications, nothing is shown
and no event is sent, so the backend's fallback decides (that is the point of the product).

## 8. Payload contract

Your backend sends a normal alert push with the tracking id at the top level (spec §6):

```json
{ "aps": { "alert": { "title": "Order shipped", "body": "Tap to see it" }, "mutable-content": 1 },
  "rp_tid": "6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11" }
```

- `mutable-content: 1` is required: it makes iOS start the NSE. Without it the push is **not tracked** and the fallback
  decides.
- `rp_tid` is at the top level of the payload, not inside `aps`.
- No silent (`content-available`) pushes: the SDK does not rely on them and does not use background fetch.

## 9. Delivery guarantees, retries and limits

- Events are persisted in the queue before `didReceive` / `trackOpen` return, so they survive a process kill. The queue
  holds up to 500 events (oldest dropped with a WARN) and is sent in batches of at most 50. An event is never queued
  twice for the same `trackingId` and type.
- Pending events are flushed at `configure` (once per launch), after `register`, after each new event and at every NSE
  run. There is no polling, no `BGTaskScheduler`, no periodic work: **retry timing is the next app launch or the next
  NSE run**. `429` is honoured through `Retry-After` (up to 1 hour); `5xx` and network errors keep the events. An event
  the server never acknowledges (two truncated answers) is dropped with a WARN, as on Android.
- The app-side flush runs inside `ProcessInfo.performExpiringActivity` and is bounded to 25 s, so a flush never holds
  its lock when iOS suspends the app.

Known limits:

- **Notifications disabled** for the app: nothing is shown, no `DELIVERED`; the fallback decides.
- **A push arriving before the first unlock after a reboot cannot be reported**: the Keychain item is not readable yet
  (a locked device never clears the registration; the event is reported later).
- **An NSE killed by the system before the send**: the event is already queued and is reported at the next app launch or
  NSE run.
- **A payload without `mutable-content`** is not tracked, so the fallback decides.
- The Simulator does not run an NSE for `xcrun simctl push`; the sample app's debug URL runs the same helper in the app
  process (see [testing.md](testing.md)).

## 10. Logging

Subsystem `io.reliablepush`, category `ReliablePush` (`os_log`), message `event=<name> key=value ...`. Names:
`device.registered`, `token.refreshed`, `device.revoked`, `tracking.event_queued`, `tracking.event_sent`, and at
error level `tracking.event_dropped`, `tracking.not_registered`, `auth.failed`, `rate_limit.exceeded`, `token.unreadable`,
`extension.time_expired`. Below that level is logged only in `DEBUG` builds of the package. Tokens, ID tokens, user ids
and notification title or body are never logged; the `rp_tid` and the device id are public.

## 11. App Store privacy

The package ships `PrivacyInfo.xcprivacy`: no tracking, no tracking domains; collected data types **Device ID** and
**Product Interaction**, both linked to the user, purpose **App Functionality**, not used for tracking; required-reason
API `NSPrivacyAccessedAPICategoryUserDefaults` with reason `1C8F.1` (App Group). In App Store Connect declare the same:
Device ID (the backend's device id) and Product Interaction (delivered / opened events), linked to the user, App
Functionality, not used for tracking. Notification content is never sent.
