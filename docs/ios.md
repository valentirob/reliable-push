# iOS SDK guide

The iOS SDK (Swift package `ReliablePush`, iOS 15.0+, Swift 6) tracks pushes that your backend sends through APNs. A
Notification Service Extension (NSE) in your app reports `DELIVERED` when the notification is about to be shown, even
when the app is not running, and the app reports `READ` when the user taps it. Events that could not be sent are
queued and retried. Apple frameworks only (Foundation, UserNotifications, Security, OSLog); the stripped arm64 library
is about 293 KB (292,896 bytes; CI gate: under 307,200 bytes). The gap to the gate is less than one 16 KB page and
only about 440 bytes are left in the last page, so the next SDK addition will likely cross it ([TODO.md](TODO.md) 4.11).

Your backend generates a tracking id, registers the notification with reliable-push and sends the APNs payload with
that id as `rp_tid` (the payload jar builds it): see [integration-guide.md](integration-guide.md) §1 and §2. This page
is the app side.

**A sidecar inside your app.** The SDK only observes your pushes:
- `ReliablePushExtension.didReceive` returns `false` for a notification without `rp_tid` and leaves it to your code;
  for such a notification it reads nothing, not even the stored configuration.
- For a tracked notification it always calls the content handler, exactly once, at most `extensionSendTimeout` after
  the push (2 s by default), and at once when there is nothing to send or an SDK step fails. Display never depends on
  tracking.
- The receive path never renews the device token: not in the NSE, not in any queue flush. Renewal happens only in
  `ensureRegistered`, which your app calls (section 4).

If the reliable-push backend cannot be reached, your notifications are shown as usual and the events wait in the App
Group queue. The flow diagram is in [overview.md §8](overview.md#8-ios-sdk).

> **Status.** This page describes `0.2.0` (in development on `develop`, not tagged yet; the last release is `0.1.2`),
> tested on the iOS Simulator locally (package tests, end-to-end suite) and in CI (`ios-ci.yml`: package tests, sample
> builds, size gate). SwiftPM by git tag is the only distribution channel (no
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
  retry-not-before, and `renewalNeeded`, set when the backend rejected the token on an event send, so a `401` seen by
  the NSE is acted on by the app).
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

## 4. Keep the device registered: `ensureRegistered`

```swift
// at app start or foreground, and right after login; returns at once, the work runs in the background
ReliablePush.ensureRegistered(idTokenProvider: { await session.currentIdToken() }) { result in   // main actor
    switch result {
    case .success(let deviceId): break        // registered now, or the device token was renewed
    case .alreadyRegistered: break            // valid token, nothing sent
    case .notLoggedIn: break                  // a registration was needed and the provider returned nil
    case .failed(let httpStatus): break       // nil = network error, Keychain unreadable, not configured...
    }
}
// or: let result = await ReliablePush.ensureRegistered(idTokenProvider: { ... })

ReliablePush.logout { confirmed in }   // revokes at the backend (best effort), clears token, registration and queue
```

The SDK renews the device token **only here**: the NSE and the event sends never do (no network call is added before a
notification is shown). Call `ensureRegistered` where it suits your app, at least at app start or foreground and right
after login. It decides locally, with no network call unless needed, in this order:

1. no device token, or the stored one has expired: it calls `idTokenProvider`. `nil` (nobody logged in) gives
   `.notLoggedIn` and nothing else; an ID token gives `POST /v1/devices`;
2. the token is still valid, and either an event send was answered `401` (`renewalNeeded`, also when the NSE saw it) or
   less than 20 % of its validity is left: `POST /v1/devices/token:refresh`. A `401` there takes path 1;
3. otherwise `.alreadyRegistered`, no call.

On success the token is stored, `renewalNeeded` is cleared and the queued events are sent. Only one run at a time: a
call while one is in progress joins it (its provider is not called). It never throws and never blocks the caller.
`idTokenProvider` returns your OIDC ID token (or a signed grant, as the harness uses), or `nil` when nobody is logged in;
it is called only on path 1, and the token is never logged. A provider that has not answered within 30 s counts as
`nil` (`.notLoggedIn`); its late answer is ignored.

Until the app calls `ensureRegistered`, the SDK keeps sending events with the token it has, even an expired one: the
backend accepts it on `POST /v1/events` (and on the logout revoke) for a grace period after expiry (90 days by default,
per app). Past that, or after a revoke, the backend answers `401`: the events stay queued and the next `ensureRegistered`
registers again and sends them. So an app that is not opened for a long time keeps reporting `DELIVERED`; an app that
never calls `ensureRegistered` stops after the grace period.

`register(idToken:)` stays for an explicit registration (it registers whatever is stored). Call `logout()` on a user
switch: it clears the token, so the next `ensureRegistered` registers the new user.

**Upgrading from 0.1.x:** the SDK no longer renews the token on its own while receiving or sending, so an app that
never calls `ensureRegistered` (or `register`) loses tracking once the token is past expiry plus the backend's grace
period (default 90 days). `extensionSendTimeout` defaults to 2 s instead of 5 s. `RegisterResult` gained
`.notLoggedIn` and `.alreadyRegistered`: an exhaustive `switch` must handle them.

`ReliablePush.deviceId` is `nil` while not registered. It is read from the Keychain record, so it is also `nil` after a
backup restore or a move to a new phone (the record is `ThisDeviceOnly`); `ensureRegistered` then takes path 1.
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
  as WARN), so your extension can handle its own pushes. The stored configuration (App Group, Keychain) is read only
  after that check, so your own pushes cost nothing and a misconfigured App Group logs `sdk.app_group_unavailable` only
  for tracked ones. Otherwise it returns `true` and calls the handler **exactly
  once**: after the send, after `ReliablePushOptions.extensionSendTimeout` (default 2 s, clamped to 0...25), or at
  `serviceExtensionTimeWillExpire`, whichever comes first; at once when there is nothing to send.
- The order is: `rp_tid` check → stored configuration → `customize` → registered check → notification settings →
  `DELIVERED` persisted → **one** `POST /v1/events` (this notification's event first) → content handler.
- `DELIVERED` is recorded only when the device is registered and the notification will actually be shown (alerts
  authorized); it is persisted in the queue before `didReceive` returns, then sent in the time left. The settings read is
  capped at 2 s (or at `extensionSendTimeout` when that is shorter) and counts against the same budget, so the handler
  runs within about `extensionSendTimeout + 0.5 s` in every case. A read that does not answer in time counts as shown
  (iOS shows the content it gets back); only an actual "not shown" answer records nothing.
- The tracking id is normalized to lower case (the form the backend stores and echoes): `customize` receives it, and
  the queued, sent and acknowledged ids all use it, whatever the case of `rp_tid` in the payload.
- The NSE sends with the stored token as it is and **never renews it**. A `401` leaves the event queued and sets
  `renewalNeeded`; the app's next `ensureRegistered` renews the token and sends it.
- Any SDK step that fails (Keychain, queue file, App Group) hands the notification back at once with the best content
  so far. A crash in your own `customize` closure cannot be caught: iOS then shows the original content.
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

**What the user sees when something fails.** The notification is always shown when iOS would show it; only tracking
changes:

| Case | The user sees | Tracking |
|---|---|---|
| Backend slow or not answering | the notification after about 2 s (at most `extensionSendTimeout` + 0.5 s) | `DELIVERED` queued, sent at the next app launch or NSE run |
| Network error | the notification at once | `DELIVERED` queued, sent later |
| Token rejected (`401`: past the grace period, or revoked) | the notification as usual | `DELIVERED` queued, `renewalNeeded` set; sent after the app's next `ensureRegistered` |
| Not registered (never registered, logged out) | the notification at once | nothing recorded (WARN `tracking.not_registered`); the fallback decides |
| No stored configuration, or the App Group cannot be opened | the original content at once (no `customize`) | nothing recorded |
| Keychain unreadable | the notification at once | `DELIVERED` queued if the device was registered, sent later by the app |
| Queue file corrupt | the notification as usual | the corrupt file is reset (older events lost, WARN); this event is sent |
| Queue file cannot be written | the notification at once | nothing recorded (WARN); the fallback may fire although the user saw it |
| Notification settings read hangs | the notification after about 2 s (at most `extensionSendTimeout` + 0.5 s) | `DELIVERED` queued (counted as shown: iOS shows the content), sent later |
| Notifications disabled | nothing (iOS decides) | nothing recorded; the fallback decides |
| iOS ends the extension (`serviceExtensionTimeWillExpire`) | the best content so far | `DELIVERED` already queued, sent later |
| Your `customize` closure crashes | the original content (iOS) | nothing recorded (`customize` runs before `DELIVERED` is persisted); the fallback may fire |

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

Your backend sends a normal alert push with the tracking id at the top level. The payload jar builds it
(`ApnsPayloads.build`) or adds the two fields to your own payload (`ApnsPayloads.decorate`):
[integration-guide.md §2](integration-guide.md#2-build-the-push-with-the-payload-jar).

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
- Pending events are flushed at `configure` (once per launch), after a successful `register` or `ensureRegistered`,
  after each new event and at every NSE run (one send there). No flush renews the device token: a `401` keeps the
  events and sets `renewalNeeded` (section 4). There is no polling, no `BGTaskScheduler`, no periodic work: **retry timing is the next app launch or the next
  NSE run**. `429` is honoured through `Retry-After` (up to 1 hour; a stored back-off further away than that, for example
  after the device clock was corrected, counts as expired); `5xx` and network errors keep the events. An event
  the server never acknowledges (two truncated answers) is dropped with a WARN, as on Android.
- The app-side flush runs inside `ProcessInfo.performExpiringActivity` and is bounded to 25 s, so a flush never holds
  its lock when iOS suspends the app.
- One flush runs at a time across the app and the NSE (`flush.lock`). A flush that finds another one running waits for
  it, within its own time limit (the NSE send timeout, 25 s in the app), and then sends what is left. So a burst of
  pushes does not leave a `DELIVERED` queued until the next launch. In the NSE that wait is part of the send timeout
  (`extensionSendTimeout`, default 2 s): the notification is never handed back later than that, but when the app is
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

- Info level: `device.registered`, `token.refreshed` (by `ensureRegistered` only), `device.register_skipped`
  (`reason=not_logged_in`), `device.revoked`, `tracking.event_queued`, `tracking.event_sent`, `sdk.reconfigured`.
- Error level (WARN): `device.register_skipped` with `reason=provider_timeout` (the ID-token provider did not answer
  within 30 s, which gives `.notLoggedIn`), `tracking.event_dropped`, `tracking.not_registered`, `auth.failed` (`call=events`: the token was
  rejected on a send, `renewalNeeded` is set; `call=refresh` or `call=register`), `rate_limit.exceeded`,
  `token.unreadable`, `token.unavailable`, `device.register_failed`, `device.revoked` (when the revoke was not
  confirmed), `extension.time_expired`, `sdk.app_group_unavailable`, `lock.unavailable`, `file.protection_failed`.

No `sdk.internal_error` on iOS (Android logs it when an entry point catches an exception): no iOS entry point can
throw into the SDK's code paths, so every failure is reported where it happens, with the specific WARN above.

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
