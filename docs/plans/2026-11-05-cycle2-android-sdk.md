# Cycle 2 — Android SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Non-Claude tools: use `.agents/skills/implement-task`. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the Android SDK (`android/`) that registers the device, reports `DELIVERED` from the FCM entry point (also with the app not running) and `READ` on tap, queues and retries failed events, and revokes on logout. Prove it with JVM tests in CI and with instrumented tests on a local emulator against the existing harness.

**Architecture:** A Gradle project in `android/` (separate from the Maven reactor) with three modules:
- `reliablepush`: the library, platform APIs only. The public object `ReliablePush` sits on small internal units: `DeviceApi` (HttpURLConnection + org.json), `TokenStore` (Keystore AES-GCM + private SharedPreferences), `EventQueue` (AtomicFile in `noBackupFilesDir`), `EventSender` (batch flush, 401 → one refresh), `RetryJobService` (one-off JobScheduler job with a network constraint) and `NotificationPoster`.
- `reliablepush-fcm-v2` (optional): the `AndroidConfigV2` entry point, compiled against the early-access firebase-messaging.
- `sample`: a demo app.

The SDK posts the visible notification first, persists the event, schedules the retry job, then flushes on its own executor, so the push entry point returns in milliseconds. The harness gains a grant endpoint and a "real devices" list so the sample app on an emulator registers like a simulated device. `scripts/android-e2e.sh` boots the emulator, starts the harness stack and runs the instrumented tests.

**Tech Stack:** Kotlin, Android Gradle Plugin (version pinned in Task 0, supporting compileSdk 37), Gradle wrapper, minSdk 26, compileSdk/targetSdk 37. Tests: JUnit 4, Robolectric, AndroidX Test and UiAutomator (test scope only). Push library: `com.google.firebase:firebase-messaging` (GA, `compileOnly` in the library). Mode v2: `firebase-messaging:26.1.0-eap01` from Google's local-Maven zip.

**Spec:** `docs/specs/2026-11-03-reliable-push-design.md` (§4.2 device API, §5 device JWT and rate limits, §6 payload contract, §7.1, §7.2, §9.2 budget and AAR < 100 KB, §10 mobile row, §10.1 mobile E2E, §10.2 "real devices", §11 mobile CI). Requirements: FR-4 to FR-8, FR-10, NFR-1, NFR-5, NFR-6, NFR-10. Rules: `.agents/rules/mobile-sdk.md`.

**Follow-up (not in this plan):**
- Release to Maven Central: needs a Sonatype namespace and a signing key. This plan only prepares the publication and verifies it with `publishToMavenLocal`.
- A "real sender" harness mode through FCM: needs a Firebase project and its credentials.
- The iOS SDK (cycle 3).

## Global Constraints

- Platform: Kotlin, `minSdk 26`, `compileSdk 37`, `targetSdk 37` (installed platform `android-37`). Gradle runs on JDK 21 (`JAVA_HOME=/opt/homebrew/opt/openjdk@21`). The Android SDK is at `ANDROID_HOME` (default `$HOME/Library/Android/sdk`).
- Dependency budget (spec §9.2, rule `mobile-sdk.md`):
  - The library's runtime classpath is Kotlin stdlib only, plus platform APIs (`HttpURLConnection`, `org.json`, AndroidKeyStore AES-GCM, `JobScheduler`, `android.app.Notification`).
  - `firebase-messaging` is `compileOnly`.
  - Forbidden: AndroidX, OkHttp/Retrofit, coroutines, WorkManager, DI, Gson/Moshi, `androidx.security-crypto`.
  - Test-only dependencies (JUnit, Robolectric, AndroidX Test, UiAutomator) are allowed.
  - Task 8 checks the release AAR at **< 100 KB** in CI.
- Public API: package `io.reliablepush.android`, Kotlin `explicitApi()`. Everything else is `internal`.
- Maven coordinates: `io.reliablepush:reliable-push-android:0.1.0-SNAPSHOT` (v2 module: `reliable-push-android-fcm-v2`). Gradle module names: `reliablepush`, `reliablepush-fcm-v2`, `sample`.
- Payload contract (spec §6), mode v1: data keys `rp_tid` (UUID string), `title`, `body`. A message without `rp_tid` is not the SDK's (`handle` returns `false` and does nothing). A malformed `rp_tid` returns `false` and logs WARN.
- `DELIVERED` is reported **only if the notification was actually posted**: `NotificationManager.areNotificationsEnabled()`, and the channel importance is not `IMPORTANCE_NONE`. Otherwise there is no event and the backend's fallback decides (spec §7, known limits).
- Token rules (spec §7.1):
  - Decode `exp`/`iat` locally (base64url JSON, no signature check on the device).
  - Refresh when remaining < 20 % of `exp − iat`, or on `401`.
  - At most **one refresh per process** (one app launch).
  - A refresh answered `401` clears the registration.
- Events:
  - Batch at most 50 per `POST /v1/events`.
  - Queue cap 500 (oldest dropped, WARN).
  - Outcomes `ACCEPTED`/`DUPLICATE`/`LATE`/`NOT_FOUND`/`FORBIDDEN` remove the event (`NOT_FOUND`/`FORBIDDEN` with WARN).
  - HTTP `429` (honour `Retry-After`), `5xx` and I/O errors keep the events.
  - Other `4xx` drop the batch with WARN `tracking.event_dropped`.
  - `logout()` clears the queue.
- No polling and no periodic work. Retry is one one-off `JobScheduler` job: network constraint, minimum latency 30 s, exponential backoff, job id from `ReliablePushOptions.jobId` (default `0x52500001`). It is scheduled before every flush and cancelled when the queue is empty.
- Network:
  - Base URL must be `https`. `http` is allowed only for hosts `localhost`, `127.0.0.1`, `10.0.2.2` (emulator → host); otherwise `configure` throws `IllegalArgumentException`.
  - `instanceFollowRedirects = false`.
  - Connect and read timeouts 10 s each.
  - `User-Agent: reliable-push-android/<version>`.
- Logging: `android.util.Log`, tag `ReliablePush`, message `event=<name> key=value…`.
  - Names: `device.registered`, `token.refreshed`, `device.revoked`, `tracking.event_queued`, `tracking.event_sent`, `tracking.event_dropped` (WARN), `tracking.not_registered` (WARN), `auth.failed` (WARN), `rate_limit.exceeded` (WARN), `token.unreadable` (WARN).
  - Below WARN only when the host app is debuggable (`ApplicationInfo.FLAG_DEBUGGABLE`).
  - Never log tokens, ID tokens, user ids or notification title/body. `rp_tid` and the device id are allowed.
- Store policy (NFR-5, rule `mobile-sdk.md`):
  - No hidden notifications in mode v1.
  - `NotificationResponse.suppress()` is never used in mode v2.
  - Data sent: `deviceId`, `trackingId`, event type, timestamp, app id. This is documented for Play Data Safety (Task 8).
- E2E is local only, never in CI:
  - `scripts/e2e.sh` stays at `E2E: 17/17 passed`.
  - `scripts/android-e2e.sh` runs the emulator suite against the harness stack, reached from the emulator at `10.0.2.2` (`8080` backend, `8090` harness).
  - Default AVD `Medium_Phone_API_37.0` (override `ANDROID_AVD`).
  - Never restart or resize Colima without the maintainer's OK.
- Versions:
  - Pinned once in Task 0 (`android/gradle/libs.versions.toml` and the Gradle wrapper with `distributionSha256Sum`), each the latest release at least two weeks old.
  - Exception, documented: the v2 EAP artifact, pinned by its zip SHA-256.
  - Nothing dynamic (`+`, `latest`).
- Gitflow:
  - All tasks commit to `feature/cycle2-android-sdk`, cut from `feature/plan-cycle2`.
  - Conventional Commits with scope `android` / `harness` / `ci` / `docs`.
  - Tick this file's checkboxes in the same commit.
  - The merge line reports `E2E: 17/17 passed` and `Android E2E: N/N passed`.

## Review Focus

1. **Notifications blocked** (Android 13+ `POST_NOTIFICATIONS` denied, app notifications off, channel set to none):
   - No crash, nothing posted and no `DELIVERED` sent, so the fallback fires.
   - Tests: Task 4 (Robolectric) and Task 6 (`notificationsBlockedMeansFallback` on the emulator).
2. **Offline, backend down, or process killed after the push**:
   - The event survives in the queue and the retry job sends it once the network is back, exactly once per event.
   - Tests: Task 3 (queue persistence across instances, job scheduled before the flush) and Task 6 (`offlineDeliveryIsSentWhenNetworkReturns`, airplane mode).
3. **Unreadable stored token** (backup restored on a new device, Keystore key invalidated, corrupt prefs):
   - No crash; treated as not registered, WARN `token.unreadable`, `register` works again.
   - Test: Task 2 (`unreadableTokenIsTreatedAsNotRegistered`).
4. **Foreign or malformed pushes** (no `rp_tid`, `rp_tid` not a UUID, missing title/body, many pushes at once):
   - Untracked messages are left to the host.
   - A malformed id is ignored with WARN.
   - A missing title/body falls back to the app label and an empty body.
   - Concurrent `handle` calls never lose or duplicate queued events.
   - Tests: Task 4 and Task 3 (`concurrentEnqueueKeepsEveryEvent`).
5. **Unsafe transport**:
   - An `http://` base URL to a non-local host is rejected at `configure`.
   - A `3xx` from the backend is not followed, so the device token is never sent to a redirect target.
   - Tests: Task 1 (`rejectsCleartextToRemoteHost`, `doesNotFollowRedirects`).

---

### Task 0: Gradle project and version pins

**Files:**
- Create:
  - `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`
  - `android/gradle/libs.versions.toml`, `android/gradle/wrapper/*`, `android/gradlew{,.bat}`
  - `android/reliablepush/build.gradle.kts`, `android/reliablepush/src/main/AndroidManifest.xml`, `android/reliablepush/consumer-rules.pro`
  - `android/sample/build.gradle.kts` (an empty app for now)
  - `android/README.md`, `android/.gitignore`

- [ ] **Step 1: Pick the versions** (each the latest stable release at least two weeks old; record each with its release date in the report):
  - The Gradle wrapper (with `distributionSha256Sum`).
  - An AGP that supports `compileSdk 37`. If it has built-in Kotlin support, use it; otherwise pin `org.jetbrains.kotlin.android`.
  - GA `firebase-messaging`, JUnit 4, Robolectric (supporting SDK 37, or the highest it supports, used through `@Config(sdk = …)`), `androidx.test` runner/rules/ext-junit/orchestrator, `androidx.test.uiautomator`.
- [ ] **Step 2: Configure.**
  - Library namespace `io.reliablepush.android`, `explicitApi()`, `buildFeatures.buildConfig = false`.
  - The SDK version string comes from `gradle.properties` `VERSION_NAME=0.1.0-SNAPSHOT`.
  - `firebase-messaging` is `compileOnly`, with `testImplementation` for Robolectric tests.
  - `minSdk 26`, Java/Kotlin target 17, unit tests `isIncludeAndroidResources = true`.
  - The library manifest declares `android.permission.INTERNET` and `RetryJobService` (`android.permission.BIND_JOB_SERVICE`, `exported=false`). The class follows in Task 3; until then keep the manifest entry out or the build fails.
- [ ] **Step 3: Smoke test.**
  - `VersionTest.versionMatchesGradleProperty()` (Robolectric): `ReliablePush.VERSION == "0.1.0-SNAPSHOT"`. A `public const val` on the object is the only code in this task.
  - Run: `cd android && ./gradlew build` → BUILD SUCCESSFUL (lint, unit tests, debug and release AAR).
  - `./gradlew :reliablepush:dependencies --configuration releaseRuntimeClasspath` lists only `org.jetbrains.kotlin:kotlin-stdlib` (and its annotations).
- [ ] **Step 4: Docs.** `android/README.md`: layout, prerequisites (JDK 21, Android SDK, `ANDROID_HOME`), commands. `AGENTS.md` rows follow in Task 8.
- [ ] **Step 5: Commit** `build(android): gradle project and pinned versions`

### Task 1: Device API client

**Files:**
- Create: `android/reliablepush/src/main/java/io/reliablepush/android/internal/{DeviceApi.kt,HttpTransport.kt,ApiResult.kt,BaseUrl.kt}`
- Test: `android/reliablepush/src/test/java/io/reliablepush/android/internal/{DeviceApiTest.kt,BaseUrlTest.kt}`, with a JDK `com.sun.net.httpserver.HttpServer` test helper `TestBackend.kt`

**Interfaces:**
- Produces:
  - `internal object BaseUrl { fun validate(url: String): String }`: normalised, no trailing slash, throws `IllegalArgumentException`.
  - `internal class DeviceApi(baseUrl: String, appId: String, userAgent: String)` with:
    - `register(idToken: String, deviceId: String?): ApiResult<Registration>`
    - `refresh(token: String): ApiResult<IssuedToken>`
    - `revoke(token: String): ApiResult<Unit>`
    - `sendEvents(token: String, events: List<QueuedEvent>): ApiResult<List<EventOutcome>>`
  - `Registration(deviceId: String, deviceToken: String, expiresAt: Long)`, `IssuedToken(deviceToken: String, expiresAt: Long)`, `QueuedEvent(trackingId: String, type: String /* DELIVERED|READ */, occurredAtMillis: Long)`.
  - `EventOutcome(trackingId: String, outcome: String)`.
  - `sealed interface ApiResult<T>` = `Ok(value)` | `Http(status: Int, retryAfterSeconds: Long?)` | `NetworkError`.
  - Body shapes follow `backend/src/main/resources/openapi.yaml` (`/v1/devices`, `/v1/devices/token:refresh`, `/v1/devices/me`, `/v1/events`); `platform` is `ANDROID`.

- [ ] **Step 1: Failing tests** (Robolectric, so `org.json` is real):
  - `registerSendsAppIdIdTokenPlatformAndParsesToken()`
  - `registerReusesStoredDeviceId()`
  - `refreshSendsBearerToken()`
  - `revokeExpects204()`
  - `sendEventsParsesPerItemOutcomes()`
  - `retryAfterIsParsedOn429()`
  - `serverErrorAndTimeoutMapToHttpAndNetworkError()` (the server sleeps longer than a test-sized read timeout; the timeout is a constructor parameter, default 10 s)
  - `doesNotFollowRedirects()` (the server answers 302 to another path that records any call: zero calls, result `Http(302)`)
  - `rejectsCleartextToRemoteHost()`: `http://example.com` throws; `http://10.0.2.2:8080`, `http://localhost`, `https://x.example` pass.
  - `neverLogsTokens()`: capture `ShadowLog`; no log line contains the id token or device token.
- [ ] **Step 2: Run** `./gradlew :reliablepush:testDebugUnitTest` → FAIL.
- [ ] **Step 3: Implement** with `HttpURLConnection`.
  - `Content-Type: application/json` and `Accept: application/json, application/problem+json`.
  - Bodies are read with a 64 KB cap.
  - Connections are always disconnected in `finally`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(android): device api client`

### Task 2: Token storage and refresh rule

**Files:**
- Create: `android/reliablepush/src/main/java/io/reliablepush/android/internal/{TokenStore.kt,TokenCipher.kt,KeystoreTokenCipher.kt,DeviceToken.kt,RefreshGate.kt}`
- Test: `src/test/.../{TokenStoreTest.kt,DeviceTokenTest.kt,RefreshGateTest.kt}`; instrumented `android/reliablepush/src/androidTest/java/io/reliablepush/android/internal/KeystoreTokenCipherTest.kt`

**Interfaces:**
- Produces:
  - `internal interface TokenCipher { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(blob: ByteArray): ByteArray }`.
  - `KeystoreTokenCipher`: AndroidKeyStore AES-256-GCM, alias `io.reliablepush.devicetoken`, a 12-byte IV stored as a prefix, no user authentication.
  - `internal class TokenStore(context: Context, cipher: TokenCipher)` with:
    - `save(state: Registration)`
    - `load(): StoredRegistration?`
    - `clear()`
    - `saveConfig(baseUrl: String, appId: String)`
    - `loadConfig(): Pair<String, String>?`
  - Prefs name `io.reliablepush.store`, `MODE_PRIVATE`. Only the device token is encrypted; `deviceId` and `expiresAt` are plain.
  - `internal data class StoredRegistration(val deviceId: String, val token: DeviceToken)`.
  - `internal data class DeviceToken(val raw: String, val issuedAt: Long, val expiresAt: Long)` with `companion fun parse(raw: String): DeviceToken?` and `fun needsRefresh(nowMillis: Long): Boolean` (remaining < 20 % of `expiresAt − issuedAt`, or expired).
  - `internal object RefreshGate { fun tryAcquire(): Boolean }`: true once per process. `reset()` is `@VisibleForTesting`.

- [ ] **Step 1: Failing tests:**
  - `needsRefreshBelowTwentyPercent()`: token with iat 0, exp 100 s → false at 80 s, true at 81 s; expired → true.
  - `parseRejectsMalformedToken()`
  - `storeRoundTripsThroughCipher()`: a fake XOR cipher; the token is never stored in plain text (read the raw prefs).
  - `unreadableTokenIsTreatedAsNotRegistered()`: the cipher throws on decrypt → `load()` returns null, prefs cleared, WARN `token.unreadable`.
  - `refreshGateAllowsOncePerProcess()`
  - Instrumented `KeystoreTokenCipherTest.roundTripAndTamperDetection()`: a flipped byte → `AEADBadTagException`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** unit tests → PASS. The instrumented test runs in Task 6's emulator run (record it there).
- [ ] **Step 5: Commit** `feat(android): encrypted token store and refresh rule`

### Task 3: Event queue, sender and retry job

**Files:**
- Create: `android/reliablepush/src/main/java/io/reliablepush/android/internal/{EventQueue.kt,EventSender.kt,RetryJobService.kt,RetryScheduler.kt}`
- Modify: the library `AndroidManifest.xml` (the `RetryJobService` entry from Task 0)
- Test: `src/test/.../{EventQueueTest.kt,EventSenderTest.kt,RetrySchedulerTest.kt}`

**Interfaces:**
- Consumes: `DeviceApi`, `TokenStore`, `DeviceToken`, `RefreshGate` (Tasks 1-2).
- Produces:
  - `internal class EventQueue(dir: File)` (file `reliablepush/events.json` under `context.noBackupFilesDir`, `android.util.AtomicFile`, all methods `@Synchronized`): `add(event: QueuedEvent)`, `peek(max: Int): List<QueuedEvent>`, `remove(ids: Collection<String>)` (key = `trackingId + type`), `clear()`, `size()`. The cap is 500, dropping the oldest.
  - `internal class EventSender(api: DeviceApi, store: TokenStore, queue: EventQueue, clock: () -> Long)` with `fun flush(): FlushResult`, where `FlushResult` is `Done | RetryLater(delaySeconds: Long?) | NotRegistered`.
    - It refreshes first when `needsRefresh` and the gate allows.
    - It sends batches of 50 until the queue is empty or a batch fails.
    - On `401` it refreshes once (if the gate allows) and retries the batch once; a refresh `401` clears the registration → `NotRegistered`.
  - `internal class RetryScheduler(context: Context, jobId: Int)` with `schedule(minLatencySeconds: Long = 30)` (`NETWORK_TYPE_ANY`, exponential backoff 30 s, `setPersisted(false)`) and `cancel()`.
  - `RetryJobService : JobService`: loads the config from `TokenStore`, runs `EventSender.flush()` on a background thread, `jobFinished(params, needsReschedule = result is RetryLater)`.

- [ ] **Step 1: Failing tests:**
  - `queueSurvivesNewInstance()`
  - `capDropsOldestWithWarn()`
  - `concurrentEnqueueKeepsEveryEvent()` (8 threads × 50 adds → 400 distinct)
  - `flushRemovesAcceptedDuplicateLateNotFoundForbidden()`
  - `flushKeepsEventsOn429And5xxAndNetworkError()` (with `retryAfterSeconds` passed through)
  - `flushDropsBatchOnOther4xx()`
  - `flushBatchesFifty()` (120 events → 3 calls)
  - `on401RefreshesOnceAndRetries()`
  - `refreshAnswered401ClearsRegistration()`
  - `secondRefreshInSameProcessIsNotAttempted()`
  - `schedulesJobWithNetworkConstraintAndLatency()` (Robolectric `ShadowJobScheduler`)
  - `cancelRemovesJob()`
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(android): persistent event queue with job scheduler retry`

### Task 4: Public API, notification and v1 entry point

**Files:**
- Create:
  - `android/reliablepush/src/main/java/io/reliablepush/android/{ReliablePush.kt,ReliablePushOptions.kt,ReliablePushMessaging.kt,RegisterResult.kt}`
  - `.../internal/{NotificationPoster.kt,SdkLog.kt,Sdk.kt}`
- Test: `src/test/.../{ReliablePushTest.kt,ReliablePushMessagingTest.kt,NotificationPosterTest.kt,SdkLogTest.kt}`

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces (public API; KDoc on every public symbol):
  - `public object ReliablePush`:
    - `const val VERSION`
    - `fun configure(context: Context, baseUrl: String, appId: String, options: ReliablePushOptions = ReliablePushOptions())`: call it in `Application.onCreate`. It is idempotent and persists the config for the job.
    - `fun register(idToken: String, callback: ((RegisterResult) -> Unit)? = null)`: async; the callback runs on the main thread. A successful register flushes the queue.
    - `fun handle(context: Context, data: Map<String, String>, fallbackTitle: String? = null, fallbackBody: String? = null): Boolean`: returns within milliseconds. Order: validate `rp_tid`, post the notification, and only if it was posted enqueue `DELIVERED`, schedule the job, then flush on the SDK executor. When not registered it posts but does not enqueue (WARN `tracking.not_registered`).
    - `fun trackOpen(intent: Intent?)`: reads extra `rp_tid`, enqueues `READ`, flushes, and removes the extra so a recreated activity does not repeat it.
    - `fun logout(callback: ((Boolean) -> Unit)? = null)`: best-effort revoke, then clear the token, the queue and the job.
    - `val deviceId: String?`
  - `public data class ReliablePushOptions(val channelId: String = "reliablepush_default", val channelName: String = "Notifications", val smallIcon: Int = 0 /* 0 → app icon */, val launchActivity: Class<out Activity>? = null /* null → launcher activity */, val jobId: Int = 0x52500001, val customizer: ((Notification.Builder, String /* rp_tid */) -> Unit)? = null)`
  - `public sealed interface RegisterResult`: `Success(deviceId: String)` | `Failed(httpStatus: Int?)`
  - `public object ReliablePushMessaging { fun onMessageReceived(context: Context, message: RemoteMessage): Boolean }`: delegates to `handle(context, message.data, message.notification?.title, message.notification?.body)`. Hosts call it from their `FirebaseMessagingService.onMessageReceived`; if it returns `false` the message is the host's.
  - For other entry points (the v2 module in Task 7 cannot use the library's `internal` classes): public but opt-in (`@RequiresOptIn` annotation `@ReliablePushEntryPointApi`, not meant for app code):
    - `ReliablePush.prepare(context, data, fallbackTitle, fallbackBody): PreparedNotification?` — null when `rp_tid` is missing or malformed; `PreparedNotification(trackingId: String, tag: String, id: Int, notification: Notification, canBeShown: Boolean)`.
    - `ReliablePush.confirmShown(prepared)` — enqueue `DELIVERED`, schedule the job, flush.
    - `handle` is `prepare`, then post, then `confirmShown` when `canBeShown`.
  - Notification: channel created on first post. Notification id = `rp_tid.hashCode()`, tag `rp`. Content intent is `PendingIntent.getActivity` (immutable) on the launch activity, with extra `rp_tid`, `autoCancel`. Title falls back to `data.title`, then `fallbackTitle`, then the app label; body falls back to `data.body`, then `fallbackBody`, then `""`.

- [ ] **Step 1: Failing tests** (Robolectric, `TestBackend` from Task 1):
  - `handleIgnoresMessageWithoutRpTid()` (returns false, nothing posted, nothing queued)
  - `handleIgnoresMalformedRpTid()` (WARN)
  - `handlePostsNotificationThenQueuesDelivered()` (`ShadowNotificationManager` shows 1 notification with title/body; the backend receives `DELIVERED` for the id)
  - `notificationsDisabledMeansNoDelivered()` (`setNotificationsEnabled(false)` → false, nothing queued)
  - `blockedChannelMeansNoDelivered()`
  - `missingTitleUsesAppLabel()`
  - `notRegisteredPostsButDoesNotQueue()`
  - `trackOpenSendsReadOnceAndConsumesExtra()`
  - `registerStoresTokenAndCallsBackOnMainThread()`
  - `logoutRevokesAndClearsQueueAndJob()`
  - `configureRejectsCleartextRemoteUrl()`
  - `releaseBuildLogsOnlyWarnAndAbove()` (`SdkLog` with debuggable false → no INFO line)
  - `messagingAdapterUsesNotificationFieldsAsFallback()`
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** with a single-thread executor owned by `Sdk` and a main-thread `Handler` for callbacks. **Step 4: Run** `./gradlew build` → PASS.
- [ ] **Step 5: Commit** `feat(android): public api, notification and fcm v1 entry point`

### Task 5: Harness — grants and real devices

**Files:**
- Modify: `harness/src/main/java/io/reliablepush/harness/console/ConsoleApiController.java`, `harness/src/main/java/io/reliablepush/harness/sender/PayloadPreview.java`, the console static files (devices table and push preview), `docs/testing.md` (console section)
- Create: `harness/src/main/java/io/reliablepush/harness/devices/RealDeviceRegistry.java`
- Test: `harness/src/test/java/io/reliablepush/harness/console/RealDevicesIT.java`, `PayloadPreviewTest` (extend)

**Interfaces:**
- Produces:
  - `POST /console/api/grants {appId, userId}` → `201 {grant, expiresAt}`, minted by `GrantMinter`.
    - The grant is never logged and is added to the log-hygiene list like every minted grant.
    - The existing `ConsoleRequestGuard` applies (JSON body, local only).
    - The harness port stays bound to `127.0.0.1`. The emulator reaches it through `10.0.2.2`, which is host loopback.
  - `POST /console/api/real-devices {appId, userId, deviceId, platform, label}` → `201`, kept in memory (at most 50).
  - `GET /console/api/devices` includes real devices with `"kind": "REAL"` (simulated ones are `"SIMULATED"`).
  - `PayloadPreview.androidDebugBroadcast(tid, title, body)`: the `adb shell am broadcast -a io.reliablepush.sample.DEBUG_PUSH -n io.reliablepush.sample/.DebugPushReceiver --es rp_tid … --es title … --es body …` line, with shell-quoted values. It is shown in the console's payload preview.

- [ ] **Step 1: Failing tests:**
  - `grantEndpointMintsVerifiableGrant()` (the HS256 signature verifies with the app's grant secret; `aud` = app, `sub` = user, `exp − iat` = 120; the real backend accepts it in Task 6's emulator run)
  - `grantRequiresJsonBodyAndKnownApp()`
  - `realDeviceAppearsInDeviceList()`
  - `debugBroadcastQuotesValues()` (a title with a quote and a space)
- [ ] **Step 2: Run** `./mvnw -B -pl harness -am verify` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `./scripts/e2e.sh` → `E2E: 17/17 passed`.
- [ ] **Step 5: Commit** `feat(harness): grants and real devices for emulator apps`

### Task 6: Sample app and emulator E2E

**Files:**
- Create:
  - `android/sample/src/main/{AndroidManifest.xml,java/io/reliablepush/sample/{SampleApp.kt,MainActivity.kt,SampleMessagingService.kt}}`
  - `android/sample/src/debug/{AndroidManifest.xml,java/io/reliablepush/sample/{DebugPushReceiver.kt,HarnessClient.kt},res/xml/network_security_config.xml}`
  - `android/sample/src/androidTest/java/io/reliablepush/sample/AndroidE2ETest.kt`
  - `scripts/android-e2e.sh`
- Modify: `android/sample/build.gradle.kts`, `scripts/lib/common.sh` (only shared helpers, no behaviour change for `e2e.sh`)

**Interfaces:**
- Consumes: the public API (Task 4), harness endpoints (Task 5).
- Produces:
  - Sample, debug build:
    - The base URL is `http://10.0.2.2:8080`, app `shop`, from `BuildConfig` fields overridable with Gradle properties `rp.baseUrl`, `rp.appId`, `rp.harnessUrl`.
    - Cleartext is allowed only to `10.0.2.2`, by the debug network security config.
    - `MainActivity` has Register (asks the harness for a grant, calls `register`, then `POST /console/api/real-devices`) and Logout buttons, shows `deviceId`, asks for `POST_NOTIFICATIONS` on Android 13+, and calls `ReliablePush.trackOpen(intent)` in `onCreate` and `onNewIntent`.
    - `DebugPushReceiver` is debug manifest only: `exported=true`, `android:permission="android.permission.DUMP"` (held by `adb shell`, not by other apps). It calls `ReliablePush.handle` with the extras.
  - Release build: only `SampleMessagingService` (delegates to `ReliablePushMessaging.onMessageReceived`) and `MainActivity`. No receiver, no harness client.
  - `./scripts/android-e2e.sh [--keep] [--test <method>]`:
    1. Checks `ANDROID_HOME`, `adb`, `emulator`, JDK 21, Docker.
    2. Starts the harness compose stack (project `reliable-push-android-e2e`, fast clock, same files and secrets generation as `e2e.sh`).
    3. Boots `ANDROID_AVD` headless (`-no-window -no-audio -no-snapshot-save`) unless a device is already attached, then waits for `sys.boot_completed`.
    4. Runs `./gradlew :reliablepush:connectedDebugAndroidTest :sample:connectedDebugAndroidTest`, passing the harness URL as instrumentation arguments.
    5. Writes `harness/build/android-e2e/summary.json` (same shape as `e2e.sh`), copies the JUnit XML and the container logs, then tears down. It stops the emulator only if it started it.
    6. Prints `Android E2E: N/N passed`; exit code 0 means all passed.

- [ ] **Step 1: Instrumented tests**. Each creates its own user id, gets a grant from the harness, registers through the SDK, asks the harness to send (`POST /console/api/push`), delivers the message by calling `ReliablePush.handle` (as `DebugPushReceiver` does), and polls `GET /console/api/notifications/{id}`:
  - `deliveredIsReported()`: NOT_DELIVERED, timeout 5 → `DELIVERED`, inbox empty after the timeout.
  - `readIsReportedOnTap()`: NOT_READ → UiAutomator opens the shade and taps the notification → `READ`.
  - `offlineDeliveryIsSentWhenNetworkReturns()`: `cmd connectivity airplane-mode enable` via `UiAutomation.executeShellCommand`, deliver, still `PENDING` after 3 s, disable airplane mode → `DELIVERED` before the timeout (timeout 60).
  - `notificationsBlockedMeansFallback()`: `pm revoke … POST_NOTIFICATIONS` → nothing posted, `FALLBACK_TRIGGERED` with one webhook; the permission is granted back in `@After`.
  - `tokenRefreshNearExpiry()`: app `news` (validity 20 s) → wait 17 s, deliver → `DELIVERED`, and the backend log has `device.token_refreshed` for the device id (harness `GET /console/api/logs`).
  - `logoutRevokesDevice()`: logout, then a push → notification posted, no `DELIVERED`, fallback fires.
  - Tests run with the AndroidX Test Orchestrator and `clearPackageData`, so each test gets a fresh process (the one-refresh-per-process gate) and empty storage. `POST_NOTIFICATIONS` is granted in `@Before` (`pm grant`).
  - The Task 2 `KeystoreTokenCipherTest` runs in the same script.
  - **Cold start (FR-6, app not running)**, run by the script after the instrumented tests as entry `cold-start-delivery`:
    1. `am start` `MainActivity` with the debug extra `autoRegisterUser=<id>` (debug build only: registers that user) and wait for the harness real-device entry.
    2. `input keyevent HOME`, then `am kill io.reliablepush.sample` (process gone, not force-stopped).
    3. Harness push, then `adb shell am broadcast` the debug push: the receiver starts the process, `SampleApp.onCreate` configures the SDK → `DELIVERED`.
- [ ] **Step 2: Run** `./scripts/android-e2e.sh` before the sample code exists → FAIL (compile or test). **Step 3: Implement** the sample and the script. **Step 4: Run** → `Android E2E: 8/8 passed` (6 instrumented + the Keystore test + cold start). Feedback loop as in `run-e2e`: at most 3 rounds, then stop and record.
- [ ] **Step 5: Commit** `feat(android): sample app and emulator e2e against the harness`

### Task 7: Mode v2 (`AndroidConfigV2`, early access)

**Files:**
- Create:
  - `android/reliablepush-fcm-v2/{build.gradle.kts,src/main/java/io/reliablepush/android/fcmv2/ReliablePushV2.kt,src/test/...}`
  - `android/scripts/fetch-fcm-eap.sh`
- Modify:
  - `android/settings.gradle.kts`: include the module only when `android/build/fcm-eap/m2repository` exists or `-PfcmEap=true`, so the default build and CI never depend on the EAP zip.
  - `harness/.../sender/PayloadPreview.java` (`fcmV2`), `docs/integration-guide.md` (mode v2)

**Interfaces:**
- Consumes: `ReliablePush.prepare` / `confirmShown` (Task 4, opt-in API).
- Produces: `public object ReliablePushV2 { fun onNotificationReceived(context: Context, message: RemoteMessage): NotificationResponse }`.
  - It calls `prepare(...)` and returns `NotificationResponse` posting `prepared.notification` with its tag and id (the factory name is taken from the EAP sources: `create(tag, id, notification)` per the FCM migration guide).
  - It calls `confirmShown` (so `DELIVERED` is queued) only when `canBeShown`.
  - A message without `rp_tid` is posted unchanged from its `notification` fields.
  - It never uses `suppress()`.
  - It returns well under 20 s: no network call on the calling thread.
  - `fetch-fcm-eap.sh` downloads `FirebaseMessaging_26.1.0-eap01.zip` from `dl.google.com` into `android/build/fcm-eap/`, verifies the SHA-256 pinned in the script, and unzips it.

- [ ] **Step 1:** Run `fetch-fcm-eap.sh`, then read the EAP API: the `onNotificationReceived` signature, the `NotificationResponse` factories, and where `rp_tid` arrives (top-level `message.data`, per the FCM `AndroidConfigV2` guide).
  - The payload shape and factory names are taken from the artifact. Where they differ from spec §6 or the harness preview (data inside `remote_notification`), the artifact and the FCM guide win: update the preview to top-level `data` plus `android_v2.remote_notification {mutable_content: true, use_as_v1_data_message: true, notification}`, and record the change for the spec in Task 8.
- [ ] **Step 2: Failing tests** (Robolectric, EAP classpath):
  - `v2PostsAndQueuesDelivered()`
  - `v2WithoutRpTidPostsUnchanged()`
  - `v2NotificationsDisabledReturnsFallbackWithoutDelivered()`
  - `v2NeverSuppresses()`
- [ ] **Step 3: Implement. Step 4: Run** `./gradlew -PfcmEap=true build` → PASS, and `./gradlew build` without the zip → PASS (module excluded).
- [ ] **Step 5: Commit** `feat(android): optional fcm android config v2 entry point (early access)`

### Task 8: CI, publication, docs and final verification

**Files:**
- Create: `.github/workflows/android-ci.yml`, `docs/android.md` (integration, Play Data Safety, known limits)
- Modify:
  - `android/reliablepush/build.gradle.kts` (`maven-publish`, `signing` from env only)
  - `.github/workflows/codeql.yml`, `.github/dependabot.yml`
  - `docs/integration-guide.md` (§4 Mobile apps → Android), `README.md`, `AGENTS.md`, `docs/testing.md`, `.agents/skills/run-e2e/SKILL.md`
  - `docs/specs/2026-11-03-reliable-push-design.md` ("Changes during implementation (plan 2)"), `docs/STATUS.md`

- [ ] **Step 1: CI.** Workflow `android-ci.yml`:
  - Triggers like `harness-ci.yml`, paths `android/**` and the workflow file; `permissions: contents: read`; actions pinned to the SHAs used in the other workflows.
  - `gradle/actions/wrapper-validation`, pinned by SHA.
  - JDK 21; `./gradlew build` in `android/`.
  - AAR size check: `reliablepush-release.aar` < 102400 bytes, else fail with the size.
  - Upload the unit test reports on failure.
  - No emulator, no v2 module.
- [ ] **Step 2: Security and updates.**
  - CodeQL gains an Android job (java-kotlin, manual build `./gradlew :reliablepush:assembleRelease`, paths `android/**`).
  - Dependabot `gradle` for `/android`.
  - Validate with `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12` → no errors.
- [ ] **Step 3: Publication.**
  - `maven-publish` with a `release` publication: AAR, sources and javadoc jars; POM with name, description, MIT license, SCM URL, developer.
  - `signing` only when `SIGNING_KEY`/`SIGNING_PASSWORD` env vars are set.
  - Run `./gradlew :reliablepush:publishToMavenLocal`; the POM lists **no** runtime dependency besides `kotlin-stdlib`.
  - Releasing to Central is out of scope (needs the maintainer's namespace and key).
- [ ] **Step 4: Docs.**
  - `docs/android.md`:
    - setup;
    - host `FirebaseMessagingService` snippet and `trackOpen` in the launch activity;
    - `POST_NOTIFICATIONS`;
    - mode v2 (early access);
    - logging names;
    - Play Data Safety entries (device id, trackingId, event type, timestamp; sent over TLS; not shared; deleted by retention);
    - backup advice (exclude `io.reliablepush.store.xml` via `dataExtractionRules`);
    - known limits (notifications disabled, force-stopped app, no Play services → fallback).
  - Integration guide §4 points to it.
  - README components table; `AGENTS.md` commands (`cd android && ./gradlew build`, `./scripts/android-e2e.sh`).
  - `docs/testing.md` Android section; the run-e2e skill mentions the Android suite.
  - Spec changes block for plan 2: v2 payload shape, harness grants and real devices, the SDK units, the one-refresh-per-process rule, DELIVERED only when posted.
- [ ] **Step 5: Final verification**, each command run once:
  - `cd android && ./gradlew build`
  - `./gradlew -PfcmEap=true build` (if the EAP zip is fetched)
  - `./mvnw -B -pl harness -am verify`
  - `cd backend && ./mvnw -B verify` (unchanged; a regression guard)
  - `./scripts/e2e.sh` → `E2E: 17/17 passed`
  - `./scripts/android-e2e.sh` → `Android E2E: 8/8 passed`
  - Record the counts and both lines in `docs/STATUS.md`.
- [ ] **Step 6: Commit** `ci(android): build, size check and codeql; docs: android sdk`, then run the `session-handoff` skill.
