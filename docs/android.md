# Android SDK guide

The Android SDK (`io.reliablepush:reliable-push-android`, Kotlin, `minSdk 26`, `compileSdk 37`) tracks pushes that your
backend sends through FCM: it posts the notification, reports `DELIVERED`, reports `READ` when the user taps it, and
retries events that could not be sent. Runtime dependencies: Kotlin stdlib only (no AndroidX, OkHttp, WorkManager or
coroutines); `firebase-messaging` is a `compileOnly` dependency, so your app supplies it. The release AAR is about 75 KB.

Your backend registers each notification with reliable-push and puts the returned `trackingId` into the FCM message as
`rp_tid`: see [integration-guide.md](integration-guide.md) §1 and §2. This page is the app side.

**A sidecar inside your app.** The SDK tracks your pushes without owning them:
- It ignores every message without `rp_tid`.
- For a tracked message it posts the notification itself (section 4): your backend sends a data-only FCM message and
  the SDK turns it into a local notification. Display never depends on tracking: if anything inside the SDK fails, it
  still posts the simplest notification it can, and nothing it throws reaches your app.
- It does all network work on its own background thread. Receiving a push never renews the device token; only
  `ensureRegistered` does (section 3).

If the reliable-push backend cannot be reached, your notifications are shown as usual and the events wait in the
queue. The flow diagram is in [overview.md §7](overview.md#7-android-sdk).

> **Status.** Version `0.2.0-SNAPSHOT` (in development; the last release is `0.1.2`), built and tested locally and in
> CI. It is not on Maven Central yet (`./gradlew :reliablepush:publishToMavenLocal` installs it into `~/.m2`; releasing
> needs the maintainer's namespace and signing key).

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

## 3. Register the device: `ensureRegistered`

```kotlin
// at app start (or when the app comes to the foreground), and right after login
ReliablePush.ensureRegistered({ deliver ->
    // called on the SDK thread only when an ID token is needed: fetch it without blocking, then deliver it once
    val user = auth.currentUser
    if (user == null) deliver(null)                                    // nobody logged in
    else user.getIdToken(false).addOnCompleteListener { deliver(if (it.isSuccessful) it.result?.token else null) }
}) { result ->                                                          // callback on the main thread
    when (result) {
        is RegisterResult.Success -> /* registered or renewed: result.deviceId */ Unit
        RegisterResult.AlreadyRegistered -> /* nothing was needed, no network call */ Unit
        RegisterResult.NotLoggedIn -> /* the provider had no ID token */ Unit
        is RegisterResult.Failed -> /* result.httpStatus, null = network error */ Unit
    }
}
ReliablePush.logout()   // clears the registration and the queue; call it when the user signs out (and on user switch)
```

`ensureRegistered` is the **only** place where the SDK renews the device token, so call it regularly; the suggested
call sites are app start or foreground, and right after login. It returns at once, never throws, and decides locally,
in this order:

1. no device token, or the token is expired: it calls your provider. `null` gives `NotLoggedIn` and nothing else; a
   token registers the device (`POST /v1/devices`);
2. the token is unexpired but has less than 20 % of its validity left, or the backend rejected it on the events
   endpoint (`401`, stored as `renewalNeeded`): it renews it (`POST /v1/devices/token:refresh`); a `401` there falls
   back to the ID-token path of step 1;
3. otherwise `AlreadyRegistered`, with no network call.

A success stores the token and sends the queued events. Only one run happens at a time: a call during a run joins it
(same result, its provider is not called). The provider is a callback because the SDK has no coroutines dependency:
call `deliver` exactly once, from any thread; extra calls are ignored, and without a call within 30 s the result is
`NotLoggedIn`. A provider that throws counts as `null`. `logout()` ends a run in progress: its callbacks get
`NotLoggedIn`, and a `deliver` that comes later is ignored, so no token is stored for the user who logged out.

`ReliablePush.register(idToken)` stays for an explicit registration (it always calls the backend). The ID token is
your OIDC ID token (or a signed grant, as the harness uses). The device token that comes back is stored encrypted
(AES-GCM, AndroidKeyStore key).

**Upgrading from 0.1.x:** the SDK no longer renews the token on its own while receiving or sending, so an app that
never calls `ensureRegistered` (or `register`) loses tracking once the token is past expiry plus the backend's grace
period (default 90 days). `RegisterResult` gained `NotLoggedIn` and `AlreadyRegistered`: an exhaustive `when` must
handle them.

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
pushes. If your push entry point is not an FCM service, call `ReliablePush.handle(context, data)` with the message's
data map instead. Title and body come from the `title` and `body` data keys (fallback: the message's `notification`
block, then the app name and an empty body).

**Data message, local notification.** Your backend sends a data-only, high-priority FCM message (no `notification`
block), so FCM always hands it to your service, also in the background, and the SDK posts the notification. This is
the market-standard way to confirm delivery on Android: services with confirmed delivery (Catapush, OneSignal, Braze,
Airship) work the same way, because it is the only way to run code when a push arrives in the background (the other
one, `AndroidConfigV2`, is not generally available, see section 10). The trade-off: the notification depends on the
app's code, so the SDK is hardened to show it whatever happens to tracking.

**What `handle` does, in this order:**

1. checks `rp_tid` (without a valid one it returns at once and reads nothing else);
2. looks up the stored configuration and registration (not configured or not registered: it shows the notification and
   records nothing, WARN `tracking.not_registered`);
3. builds the notification, with your `customizer` (not posted yet);
4. the gate, on the channel the notification will really use (your customizer may have moved it): can the app show it
   (notifications enabled, `POST_NOTIFICATIONS` granted, not paused, the channel present and neither it nor its group
   blocked)? If not, the notification is still handed to the system, which decides, nothing is recorded and `handle`
   returns `false` (section 6). So a customizer that moves the notification to a missing or blocked channel means no
   `DELIVERED` and the fallback decides, while one that moves it from a blocked SDK channel to an open one is tracked;
5. writes `DELIVERED` to the on-disk queue and schedules the retry job (disk only, milliseconds);
6. posts the prepared notification on the calling thread;
7. schedules the immediate send of `DELIVERED` on the SDK thread: one `POST /v1/events` with the stored token, no
   token renewal before or after.

**Robustness.** The whole of `handle` runs inside a catch-all. If anything throws before the notification is posted
(storage, queue, building, gate or the post), the SDK posts a minimal notification instead: the title and body (else
the app name) on the configured channel, with the `rp_tid` tap extra, without your customizer. It logs WARN
`sdk.internal_error` with the exception's class name only.

`true` means the notification was posted. `handle` returns `false` only when:
- the message is not the SDK's (no or malformed `rp_tid`);
- the gate is closed: notifications disabled for the app, `POST_NOTIFICATIONS` denied, or notifications paused;
- the final channel is unusable: missing, set to none, or in a blocked group (including a channel your customizer
  chose);
- even the minimal notification could not be posted (also when its channel cannot be created).

Nothing is recorded in the first three cases. In each case your service may show its own notification.

**The trade-off: a missed fallback rather than a duplicate one.** `DELIVERED` is on disk before the post, so it
survives the process being killed right after `handle`. The price: if posting fails after that (the system throws
while posting, and then the minimal notification fails too), the event is still sent and no fallback fires for that
push. The gate runs on the final channel before anything is recorded, so this takes a failure of the platform itself,
not a setting. The maintainer chose this over the opposite risk, a fallback (SMS, email) for a push the user did see.
Such a failure is logged (`sdk.internal_error`).

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
moment); the SDK never asks. `DELIVERED` is reported only when the notification can be shown: if the permission is
denied, app notifications are off, notifications are paused, or the channel (or its group) is blocked, the notification
is still handed to the system, which drops it, and no event is recorded, so the backend's fallback decides (that is the
point of the product). `onMessageReceived` then returns `false` (WARN `notification.not_shown`).

## 7. Delivery guarantees and retries

- `DELIVERED` is persisted in the queue before the notification is posted, and `READ` before `trackOpen` returns, so
  they survive a process kill. (`handle` called on the main thread waits at most 500 ms for the write, which runs on a
  background thread; past that it posts anyway.)
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
- No flush renews the token. The backend accepts an expired device token on `POST /v1/events` and on logout for a
  grace period (default 90 days), so a user who has not opened the app for a while is still tracked. A `401` there
  means past the grace period or revoked: the events stay queued, the SDK stores `renewalNeeded`, the retry job stops,
  and the next successful `ensureRegistered` renews the token and sends them.
- **Android 17+ (`targetSdk 37`):** reaching a backend on a private network (RFC 1918 addresses, `10.0.2.2` on the
  emulator) needs the runtime permission `ACCESS_LOCAL_NETWORK`; without it the requests time out and events stay queued.
  Production backends are public `https` hosts and do not need it; the sample declares it in its debug build only.

## 8. Logging

Tag `ReliablePush`, message `event=<name> key=value ...`. Names: `device.registered`, `token.refreshed` (only from
`ensureRegistered`), `device.revoked`, `tracking.event_queued`, `tracking.event_sent`, and at WARN
`tracking.event_dropped`, `tracking.not_registered`, `auth.failed`, `rate_limit.exceeded`, `token.unreadable`,
`device.register_failed`, `notification.not_shown` (gate closed), `notification.not_posted`,
`notification.channel_failed` (the minimal notification's channel could not be created), `tracking.persist_slow` (a
main-thread `handle` waited 500 ms for the `DELIVERED` write and posted anyway), `sdk.internal_error`
(an error caught inside the SDK; class name only). Below WARN is logged only when the
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
`data` (integration guide §2), turned into a local notification by the SDK (section 4).

Mode v2, `AndroidConfigV2` (the Android counterpart of the iOS Notification Service Extension: FCM shows the
notification and lets the app run code for it), is **not supported**. Its status: a preview SDK, distributed through
an early-access programme outside the public Maven repositories, with no announced general-availability date. It
stays a TODO (`docs/TODO.md` 2.1); the payload library is where the switch will happen. Until then FCM delivers
v2-shaped messages to apps that implement v1 through the same `onMessageReceived`, so a message carrying `rp_tid` in
its data is still tracked.

## 11. Google Play Data Safety

The SDK sends only the following to the reliable-push backend you configured (it has no third-party endpoints):

| Data | Why | Play Data Safety entry |
|---|---|---|
| Device id (assigned by the backend at registration) | associate events with the registration | Device or other IDs: collected, not shared, purpose App functionality |
| `trackingId` (`rp_tid`), event type (`DELIVERED` or `READ`), timestamp, app id | delivery and read tracking, fallback decision | App activity (in-app actions): collected, not shared, purpose App functionality |
| The user's ID token or grant, sent once at registration | authenticate the registration | covered by your own account data declaration |

Everything is sent over TLS (`https` is enforced), is not shared with third parties, is not used for advertising, and is
deleted by the backend's retention job (see [operations.md](operations.md)). Declare the data as required for the app's functionality (tracking stops on `logout()`).
The SDK posts no hidden notifications: every tracked push is posted visibly. Check the final declaration against your
own app's other SDKs.

## 12. Known limits

- Notifications disabled (permission, app setting or channel): no notification, no `DELIVERED`; the fallback fires.
- A post that fails after `DELIVERED` was persisted (the full and the minimal notification): no fallback for that push
  (section 4, the accepted trade-off).
- No `ensureRegistered` (or `register`) for longer than the token validity plus the grace period: events are rejected
  (`401`) and wait in the queue until the app renews the token.
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
