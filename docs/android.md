# Android SDK guide

The Android SDK (`io.reliablepush:reliable-push-android`, Kotlin, `minSdk 26`, `compileSdk 37`) tracks pushes that your
backend sends through FCM: it posts the notification, reports `DELIVERED`, reports `READ` when the user taps it, and
retries events that could not be sent. Runtime dependencies: Kotlin stdlib only (no AndroidX, OkHttp, WorkManager or
coroutines); `firebase-messaging` is a `compileOnly` dependency, so your app supplies it. The release AAR is about 66 KB.

Your backend registers each notification with reliable-push and puts the returned `trackingId` into the FCM message as
`rp_tid`: see [integration-guide.md](integration-guide.md) §1 and §2. This page is the app side.

**A sidecar inside your app.** The SDK only observes your pushes:
- It ignores every message without `rp_tid`.
- It never hides a tracked notification.
- It does all network work on its own background thread, and nothing it throws reaches your app.

If the reliable-push backend cannot be reached, your notifications are shown as usual and the events wait in the
queue. The flow diagram is in [overview.md §7](overview.md#7-android-sdk).

> **Status.** Version `0.1.2`, built and tested locally and in CI. It is not on Maven Central yet (`./gradlew
> :reliablepush:publishToMavenLocal` installs it into `~/.m2`; releasing needs the maintainer's namespace and signing key).

## 1. Add the library

```kotlin
// settings.gradle.kts: mavenLocal() until the library is on Maven Central
dependencies {
    implementation("io.reliablepush:reliable-push-android:0.1.2")
    implementation("com.google.firebase:firebase-messaging:<your version>")
}
```

The library's manifest adds the `INTERNET` permission and the (non-exported) retry job service. R8: the AAR ships its own
consumer rules (none needed today).

## 2. Configure it in `Application.onCreate`

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ReliablePush.configure(
            context = this,
            baseUrl = "https://push.example.com",   // the reliable-push API
            appId = "shop",                          // the app id your operator configured
            options = ReliablePushOptions(smallIcon = R.drawable.ic_stat_push),
        )
    }
}
```

- Call it on **every launch**, before anything else: `Application` runs before your `FirebaseMessagingService`, so the
  first push is already tracked. Pass the same options each time (only the base URL and app id are persisted).
- Calling it again with the same base URL and app id keeps the registration. A different base URL or app id replaces the
  stored configuration and clears the registration, the queued events and the retry job.
- Events left in the queue by an earlier launch are sent at `configure` (once per launch).
- `baseUrl` must be `https`. Plain `http` is accepted only for `localhost`, `127.0.0.1` and `10.0.2.2` (the emulator's
  host); anything else makes `configure` throw `IllegalArgumentException`. Redirects are never followed.
- `smallIcon` should be a **monochrome** drawable (white on transparent). Without it the SDK uses the app icon, which
  Android can render as a grey square.
- `ReliablePushOptions` also has `channelId`, `channelName`, `launchActivity`, `jobId` (the `JobScheduler` id, default
  `0x52500001`) and `customizer` (add colour or actions to the notification builder; it must not hide the notification).

## 3. Register the device after login

```kotlin
ReliablePush.register(idToken) { result ->   // callback on the main thread
    when (result) {
        is RegisterResult.Success -> /* result.deviceId */ Unit
        is RegisterResult.Failed -> /* result.httpStatus, null = network error */ Unit
    }
}
ReliablePush.logout()   // clears the registration and the queue; call it when the user signs out
```

`idToken` is your OIDC ID token (or a signed grant, as the harness uses). The device token that comes back is stored
encrypted (AES-GCM, AndroidKeyStore key). The SDK refreshes it before it expires and once on a `401`, at most one refresh
per app launch.

## 4. Hand FCM messages to the SDK

```kotlin
class MessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (ReliablePushMessaging.onMessageReceived(this, message)) return
        // not a Reliable Push message (no valid rp_tid), or the notification is blocked: your own handling
    }
}
```

Declare the service in your manifest as usual. Messages without `rp_tid` are ignored (`false`), so the app keeps its own
pushes. A tracked message is **never hidden**: it is shown, or blocked by the OS (then there is no `DELIVERED`); store
policy forbids hidden notifications. If your push entry point is not an FCM service, call
`ReliablePush.handle(context, data)` with the message's data map instead. Title and body come from the `title` and
`body` data keys (fallback: the app name and an empty body).

## 5. Track opens in the launch activity

The notification's tap intent opens the launch activity with `NEW_TASK | CLEAR_TOP | SINGLE_TOP` and the extra `rp_tid`.
Call `trackOpen` from **both** `onCreate` and `onNewIntent`, or taps on a running activity are missed:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ReliablePush.trackOpen(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    ReliablePush.trackOpen(intent)
}
```

`trackOpen` ignores intents without `rp_tid`. Set `ReliablePushOptions.launchActivity` if the launcher activity is not
the one that should handle taps.

## 6. Notification permission

On Android 13+ the **host app** requests `POST_NOTIFICATIONS` (declare it in your manifest and ask at a suitable
moment); the SDK never asks. `DELIVERED` is reported only when the notification was actually shown: if the permission is
denied, app notifications are off, or the channel importance is `NONE`, nothing is posted and no event is sent, so the
backend's fallback decides (that is the point of the product). `onMessageReceived` then returns `false`.

## 7. Delivery guarantees and retries

- `DELIVERED` and `READ` are persisted in the queue before `handle` / `trackOpen` return, so they survive a process kill.
- The queue holds up to 500 events (oldest dropped with a WARN) and is sent in batches of at most 50.
- Events that cannot be sent (offline, backend down, `429` with `Retry-After`, `5xx`) stay queued. One one-off
  `JobScheduler` job (network constraint, minimum latency 30 s, exponential backoff) retries them; there is no polling and
  no periodic work. The 30 s counts from the push (the moment the job is scheduled), not from the network returning:
  after a long outage the job therefore runs shortly after the network returns (the OS may add a few seconds), while an
  event queued only moments before the network returns waits up to the remaining 30 s. After repeated `5xx` or I/O
  failures the OS exponential backoff (capped at 5 hours) can delay the retry much longer. A server `Retry-After` is
  honoured up to 1 hour. A notification with a very short fallback timeout can therefore fall back first; real
  timeouts are minutes (the default is 10).
- Events from an earlier launch whose job was lost (for example after a reboot) are sent at the next `configure`.
- A refresh answered `401` clears the registration: the app must call `register` again.
- **Android 17+ (`targetSdk 37`):** reaching a backend on a private network (RFC 1918 addresses, `10.0.2.2` on the
  emulator) needs the runtime permission `ACCESS_LOCAL_NETWORK`; without it the requests time out and events stay queued.
  Production backends are public `https` hosts and do not need it; the sample declares it in its debug build only.

## 8. Logging

Tag `ReliablePush`, message `event=<name> key=value ...`. Names: `device.registered`, `token.refreshed`,
`device.revoked`, `tracking.event_queued`, `tracking.event_sent`, and at WARN `tracking.event_dropped`,
`tracking.not_registered`, `auth.failed`, `rate_limit.exceeded`, `token.unreadable`. Below WARN is logged only when the
host app is debuggable. Tokens, ID tokens, user ids and notification title or body are never logged; the `rp_tid` and the
device id are.

## 9. Backup

Do not back up the SDK's store: the device token is bound to a Keystore key that does not move to another device, so a
restored copy is unreadable (the SDK treats it as "not registered", logs `token.unreadable` and recovers on the next
`register`, but excluding it avoids the noise). Exclude `io.reliablepush.store.xml`:

```xml
<!-- AndroidManifest.xml: android:dataExtractionRules="@xml/data_extraction_rules" android:fullBackupContent="@xml/backup_rules" -->
<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup><exclude domain="sharedpref" path="io.reliablepush.store.xml"/></cloud-backup>
    <device-transfer><exclude domain="sharedpref" path="io.reliablepush.store.xml"/></device-transfer>
</data-extraction-rules>
<!-- res/xml/backup_rules.xml (Android 11 and lower) -->
<full-backup-content><exclude domain="sharedpref" path="io.reliablepush.store.xml"/></full-backup-content>
```

## 10. FCM message modes

Only mode v1 (the default) is supported: a data-only, high-priority FCM message with `rp_tid`, `title` and `body` in
`data` (integration guide §2). Mode v2 (`AndroidConfigV2`) is **not supported yet**: it waits for Google's general
availability of the API. Until then FCM delivers v2-shaped messages to apps that implement v1 through the same
`onMessageReceived`, so a message carrying `rp_tid` in its data is still tracked.

## 11. Google Play Data Safety

The SDK sends only the following to the reliable-push backend you configured (it has no third-party endpoints):

| Data | Why | Play Data Safety entry |
|---|---|---|
| Device id (assigned by the backend at registration) | associate events with the registration | Device or other IDs: collected, not shared, purpose App functionality |
| `trackingId` (`rp_tid`), event type (`DELIVERED` or `READ`), timestamp, app id | delivery and read tracking, fallback decision | App activity (in-app actions): collected, not shared, purpose App functionality |
| The user's ID token or grant, sent once at registration | authenticate the registration | covered by your own account data declaration |

Everything is sent over TLS (`https` is enforced), is not shared with third parties, is not used for advertising, and is
deleted by the backend's retention job (see [operations.md](operations.md)). Declare the data as required for the app's functionality (tracking stops on `logout()`).
The SDK posts no hidden notifications. Check the final declaration against your own app's other SDKs.

## 12. Known limits

- Notifications disabled (permission, app setting or channel): no notification, no `DELIVERED`; the fallback fires.
- App in the **force-stopped** state: Android delivers no FCM messages; the fallback fires.
- Device without Google Play services, or with FCM blocked: no push arrives; the fallback fires.
- `READ` is the tap on the SDK's notification; dismissing it is not read.
- A message handled before `configure` ran uses the stored configuration with default options (call `configure` in
  `Application.onCreate` to avoid it). `trackOpen` before the first `configure` and handled push drops the event.
- One malformed entry in the stored queue makes the SDK discard the whole queue.
- Mode v2 is not supported (section 10); there is no iOS equivalent here (see the iOS cycle).

## 13. Building and testing the SDK

`cd android && ./gradlew build` (lint, unit tests, AAR). `./scripts/android-e2e.sh` runs the emulator suite against the
harness stack; see [testing.md](testing.md). CI builds on GitHub-hosted runners without an emulator and fails if the
release AAR is 102,400 bytes or more.
