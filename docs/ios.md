# iOS SDK guide

The iOS SDK (Swift package `ReliablePush`, iOS 15.0+, Swift 6) tracks pushes that your backend sends through APNs. A
Notification Service Extension (NSE) in your app reports `DELIVERED` when the notification is about to be shown, even
when the app is not running, and the app reports `READ` when the user taps it. Events that could not be sent are
queued and retried. Apple frameworks only (Foundation, UserNotifications, Security, OSLog); the stripped arm64 library
is about 292 KB (CI gate: under 307,200 bytes).

Your backend registers each notification with reliable-push and puts the returned `trackingId` into the APNs payload as
`rp_tid`: see [integration-guide.md](integration-guide.md) §1 and §2. This page is the app side.

**A sidecar inside your app.** The SDK only observes your pushes:
- `ReliablePushExtension.didReceive` returns `false` for a notification without `rp_tid` and leaves it to your code.
- For a tracked notification it always calls the content handler, exactly once, at most `extensionSendTimeout` after
  the push (5 s by default).

If the reliable-push backend cannot be reached, your notifications are shown as usual and the events wait in the App
Group queue. The flow diagram is in [overview.md §7](overview.md#7-ios-sdk).

> **Status.** Version `0.1.2` (tag `v0.1.2`), tested on the iOS Simulator locally (package tests, end-to-end suite) and
> in CI (`ios-ci.yml`: package tests, sample builds, size gate). SwiftPM by git tag is the only distribution channel (no
> XCFramework, no CocoaPods). Real APNs delivery on a device has not been verified yet.

## 1. Add the package

The manifest `Package.swift` is at the repository root, so Xcode resolves it by URL: File > Add Package Dependencies,
enter the repository URL, pick the `ReliablePush` library and add it to **both** the app target and the NSE target. Or in
a `Package.swift`:

```swift
.package(url: "https://github.com/valentirob/reliable-push.git", from: "0.1.2"),
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
- The SDK sets the Data Protection class `completeUntilFirstUserAuthentication` explicitly on that directory, the queue
  file and its lock files (`events.lock`, `flush.lock`), so the NSE can open them on a locked device even when your app's
  default class is `NSFileProtectionComplete`.
- The device token lives in the Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: no backup, no migration, so
  a restored device starts unregistered). Its access group defaults to **the App Group id**: an App Group is a valid
  keychain access group, so no team-prefixed `keychain-access-groups` entitlement is needed. Override it with
  `ReliablePushOptions(keychainAccessGroup:)` (then pass the same options in the app and in the NSE).
- The token and the device id are saved as one Keychain record, so a reader never sees half a registration.
- Troubleshooting: an error-level `token.unavailable status=<OSStatus>` means the Keychain refused the read for a reason
  other than "device locked". `status=-34018` (`errSecMissingEntitlement`) is an entitlement problem: the App Group (or
  your `keychainAccessGroup`) is missing or misspelled in the app's or the NSE's entitlements.

## 3. Configure in `didFinishLaunching`

```swift
import ReliablePush

func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
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
  reinstalled app starts unregistered and registers again after login. When the Keychain is locked at that point (a
  launch in the background before the first unlock after a reboot, when the App Group also reads as empty), nothing is
  cleared or persisted; the next launch after the unlock does it.
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
`ReliablePush.deviceId` is `nil` while not registered. It is read from the Keychain record, so it is also `nil` after a
backup restore or a move to a new phone (the record is `ThisDeviceOnly`): gating `register` on `deviceId == nil` works.
While the Keychain is locked it returns the last known id.

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
  It is synchronous.
- `ReliablePushExtension(appGroup:options:)` takes the same `keychainAccessGroup` as the app, if you overrode it.

**An existing NSE that changes the content asynchronously** (for example, downloading an image attachment): pass your
own handler closure to `didReceive`. It finishes your work and then calls the system's handler:

```swift
override func didReceive(_ request: UNNotificationRequest,
                         withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
    let handled = reliablePush.didReceive(request) { content in
        // called exactly once: after the send, at extensionSendTimeout, or at serviceExtensionTimeWillExpire
        self.attachImage(to: content) { contentHandler($0) }   // your download, with a short timeout
    }
    if !handled {
        contentHandler(request.content)   // not a reliable-push notification: your own handling
    }
}
```

The SDK calls your closure once on every path, including at `serviceExtensionTimeWillExpire`, when iOS ends the
extension right after: keep your work bounded and call the system's handler promptly (start the download early, or skip
it when time is up).

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

**Show the SDK's notifications in the foreground.** The NSE reports `DELIVERED` before iOS decides how to present the
notification, and it cannot know whether your app is open. If your delegate does not implement `willPresent`, or returns
`[]`, iOS does not show the notification while the app is in the foreground, yet `DELIVERED` is reported and the
fallback is suppressed. Present at least the notifications that carry `rp_tid`:

```swift
nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
    if notification.request.content.userInfo["rp_tid"] != nil {
        completionHandler([.banner, .list])   // DELIVERED is already reported: show it
    } else {
        completionHandler([])                 // your own policy for other notifications
    }
}
```

**Another SDK owns the delegate** (Firebase Messaging, Braze and others often install their own
`UNUserNotificationCenterDelegate` or proxy yours): call `ReliablePush.trackOpen(response:)` from whichever delegate
method receives the response (`didReceive`), and present `rp_tid` notifications from whichever receives `willPresent`.

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
  NSE run**. `429` is honoured through `Retry-After` (up to 1 hour; a stored back-off further away than that, for example
  after the device clock was corrected, counts as expired); `5xx` and network errors keep the events. An event
  the server never acknowledges (two truncated answers) is dropped with a WARN, as on Android.
- The app-side flush runs inside `ProcessInfo.performExpiringActivity` and is bounded to 25 s, so a flush never holds
  its lock when iOS suspends the app.
- One flush runs at a time across the app and the NSE (`flush.lock`). A flush that finds another one running waits for
  it, within its own time limit (the NSE send timeout, 25 s in the app), and then sends what is left. So a burst of
  pushes does not leave a `DELIVERED` queued until the next launch. In the NSE that wait is part of the send timeout
  (`extensionSendTimeout`, default 5 s): the notification is never handed back later than before, but when the app is
  flushing at the same moment it can take up to that timeout instead of appearing at once.

Known limits:

- **Notifications disabled** for the app: nothing is shown, no `DELIVERED`; the fallback decides.
- **A push arriving before the first unlock after a reboot is not reported, and its event is lost**: neither the App Group
  configuration nor the Keychain item is readable yet, so the NSE passes the notification through untouched (a locked
  device never clears the registration). The fallback decides.
- **The app in the foreground without presenting the notification** (`willPresent` missing or returning `[]`, see
  section 6): `DELIVERED` is still reported although the user saw nothing.
- **An NSE killed by the system before the send**: the event is already queued and is reported at the next app launch or
  NSE run.
- **A payload without `mutable-content`** is not tracked, so the fallback decides.
- The Simulator does not run an NSE for `xcrun simctl push`; the sample app's debug URL runs the same helper in the app
  process (see [testing.md](testing.md)).

## 10. Logging

Subsystem `io.reliablepush`, category `ReliablePush` (`os_log`), message `event=<name> key=value ...`. A WARN is written
at `os_log` error level, the rest at info level.

- Info level: `device.registered`, `token.refreshed`, `device.revoked`, `tracking.event_queued`, `tracking.event_sent`,
  `sdk.reconfigured`.
- Error level (WARN): `tracking.event_dropped`, `tracking.not_registered`, `auth.failed`, `rate_limit.exceeded`,
  `token.unreadable`, `token.unavailable`, `device.register_failed`, `device.revoked` (when the revoke was not
  confirmed), `extension.time_expired`, `sdk.app_group_unavailable`, `lock.unavailable`, `file.protection_failed`.

Info level is logged only in `DEBUG` builds of the package. Tokens, ID tokens, user ids and notification title or body
are never logged. Public fields: `trackingId` (the `rp_tid`), `deviceId`, and the low-cardinality `reason`, `status`,
`count`, `outcome`, `type`, `call`, so a release-build WARN keeps its cause; every other field is private.

## 11. App Store privacy

The package ships `PrivacyInfo.xcprivacy`: no tracking, no tracking domains; collected data types **User ID**,
**Device ID** and **Product Interaction**, all linked to the user, purpose **App Functionality**, not used for tracking;
required-reason API `NSPrivacyAccessedAPICategoryUserDefaults` with reason `1C8F.1` (App Group). In App Store Connect
declare the same: User ID (the ID token sent at registration carries the account id, and the backend links every event
to it; most apps already declare it for their own login), Device ID (the backend's device id) and Product Interaction
(delivered / opened events), linked to the user, App Functionality, not used for tracking. Notification content is
never sent.
