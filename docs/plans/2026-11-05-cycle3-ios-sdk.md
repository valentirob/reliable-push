# Cycle 3 — iOS SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Non-Claude tools: use `.agents/skills/implement-task`. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the iOS SDK (`ios/`) as a Swift package. It registers the device, reports `DELIVERED` from the host's Notification Service Extension (NSE), including when the app is not running, and reports `READ` on tap. It queues failed events and retries them on the next launch or the next NSE run, and it revokes the device on logout. Prove it with XCTest unit tests in CI and with UI tests on a local iOS Simulator against the existing harness.

**Architecture:** Swift package `ReliablePush`. The manifest `Package.swift` sits at the **repository root** (SwiftPM only resolves a package by URL from the repository root); the sources live under `ios/`. The public facade `ReliablePush` sits on small internal units:
- `DeviceAPI`: `URLSession`, ephemeral, redirects refused.
- `KeychainStore`: generic-password items in a shared access group.
- `SharedState`: `UserDefaults(suiteName: appGroup)` for config and non-secret state.
- `EventQueue`: a JSON file in the App Group container, guarded by `flock`, because the app and the NSE are two processes.
- `EventSender`: the same batching and outcome rules as Android.
- `ReliablePushExtension`: the NSE helper. It calls the content handler exactly once.

There is no background scheduler. Pending events are flushed:
- at `configure` (once per launch);
- after `register`;
- after each new event;
- at every NSE run.

The sample app (`ios/Sample/`, a committed Xcode project) has an app target, an NSE target, a hosted unit-test target and a UI-test target.

The Simulator does **not** run an NSE for `xcrun simctl push` or `.apns` files, so the simulated path works differently. A debug-only URL (`rpsample://debug-push?...`) runs the same `ReliablePushExtension` code in the app process, then shows the resulting content as a local notification, which the UI test taps.

The harness gains a device-traffic proxy with an online/offline switch, so "offline" can be simulated: the Simulator shares the Mac's network and has no airplane mode.

An optional real-APNs mode (Task 7) sends through the APNs sandbox to the Simulator, so the OS runs the real NSE. It needs the maintainer's Apple Developer Program team, and the credentials stay only in git-ignored local files.

**Tech Stack:** Swift 6 (language mode 6, strict concurrency), SwiftPM `swift-tools-version: 6.0`, iOS deployment target 15.0. Apple frameworks only: Foundation, UserNotifications, Security, OSLog (plus UIKit/SwiftUI in the sample app only). Tests: XCTest (unit, hosted and UI tests). Build and test: `xcodebuild` and `xcrun simctl`, with the Xcode version pinned in Task 0. The harness APNs sender (Task 7) uses the JDK only: `java.net.http` over HTTP/2, with ES256 signing via `java.security`.

**Spec:** `docs/specs/2026-11-03-reliable-push-design.md`:
- §4.2 device API; §5 device JWT and rate limits; §6 payload contract (iOS: `aps.alert` + `mutable-content: 1`, `rp_tid` at the top level);
- §7.1, §7.3;
- §9.2 budget and framework < 300 KB;
- §10 mobile row, §10.1 mobile E2E, §10.2 "real devices" and "real pushes";
- §11 mobile CI;
- the plan 2 block, whose rules carry over: DELIVERED only when shown, persisted before returning, one refresh per process, no redirects.

Requirements: FR-4 to FR-8, FR-10, NFR-1, NFR-5, NFR-6, NFR-10. Rules: `.agents/rules/mobile-sdk.md`.

**Prerequisites (maintainer, before Task 0):** Xcode installed and selected (`sudo xcode-select -s /Applications/Xcode.app`), first launch done (`xcodebuild -runFirstLaunch`), and an iOS Simulator runtime installed (`xcodebuild -downloadPlatform iOS`). The agent never installs Xcode, signs in with an Apple ID or accepts licences.

**Follow-up (not in this plan):**
- A binary XCFramework release and CocoaPods: SwiftPM by git tag is the only distribution channel.
- Communication notifications and Notification Content Extensions.

## Global Constraints

- Platform: iOS 15.0+, Swift 6 language mode, `swift-tools-version: 6.0`. Root `Package.swift`:
  - library product `ReliablePush` (automatic linkage), target path `ios/Sources/ReliablePush`;
  - test target `ReliablePushTests` at `ios/Tests/ReliablePushTests`;
  - `platforms: [.iOS(.v15)]` only.
- Unit tests run on the iOS Simulator: `xcodebuild test -scheme ReliablePush -destination "platform=iOS Simulator,name=$IOS_SIMULATOR"`. The default device name is pinned in Task 0. The Command Line Tools have no XCTest, so every Swift build and test needs Xcode.
- Dependency budget (spec §9.2, rule `mobile-sdk.md`):
  - Foundation, UserNotifications, Security, OSLog. No third-party package, and no `Package.resolved` dependency.
  - The SDK never imports UIKit/SwiftUI (UIKit appears only in the sample).
  - **Size gate:** the stripped arm64 iOS dynamic library built by `ios/scripts/size.sh` (whole-module, `-O`) is **< 307,200 bytes**.
- Public API: module `ReliablePush`. Every public symbol has a doc comment; everything else is `internal`. Public types are `Sendable`.
- Version: `ReliablePush.version == "0.1.0-SNAPSHOT"`, sent as `User-Agent: reliable-push-ios/<version>`.
- Payload contract (spec §6): `rp_tid` (UUID string) at the top level of `userInfo`.
  - A notification without `rp_tid` is not the SDK's: the helpers return `false` and leave everything to the host.
  - A malformed `rp_tid` returns `false` and logs WARN.
- **Content handler:** the NSE helper calls the content handler **exactly once** on every path. That includes: not ours, not registered, network hang, error, and `serviceExtensionTimeWillExpire` during a send. The notification is never lost.
- `DELIVERED` is reported **only if the notification will be shown**, judged from the NSE's `getNotificationSettings`:
  - `authorizationStatus` is `.authorized`, `.provisional` or `.ephemeral`;
  - and at least one of `alertSetting`, `notificationCenterSetting`, `lockScreenSetting` is `.enabled`.

  Otherwise there is no event and the fallback decides. `DELIVERED` and `READ` are **persisted in the queue before the helper returns or the content handler is called**.
- Sharing between the app and the NSE:
  - App Group id passed to `configure` and to the extension helper (sample: `group.io.reliablepush.sample`).
  - The Keychain access group defaults to the **same App Group id**: app groups are valid keychain access groups, so no team-prefixed `keychain-access-groups` entitlement is needed. It can be overridden with `ReliablePushOptions.keychainAccessGroup`.
  - Keychain item: `kSecClassGenericPassword`, service `io.reliablepush.devicetoken`, account = app id, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. ThisDeviceOnly means no backup or migration, so a restored device starts unregistered.
  - `errSecInteractionNotAllowed` (device locked before first unlock) means "not available now". It **never** clears the registration. Only an undecodable item clears it, with WARN `token.unreadable`.
  - Non-secret state: `UserDefaults(suiteName:)` keys under the prefix `io.reliablepush.` (config, deviceId, expiresAt, retry-not-before).
  - Queue file: `<container>/Library/Application Support/ReliablePush/events.json`, written atomically with `FileProtectionType.completeUntilFirstUserAuthentication`. Cross-process lock: `flock` on `events.lock` beside it.
- Token rules (spec §7.1, plan 2):
  - Decode `exp`/`iat` locally.
  - Refresh when remaining < 20 % of `exp − iat`, or on `401`.
  - At most **one refresh per process** (the app and the NSE are separate processes; the backend keeps the old token valid on refresh, so concurrent refreshes are safe).
  - A refresh answered `401` clears the registration.
- Events:
  - Batch ≤ 50; queue cap 500 (oldest dropped, WARN); key `trackingId + type` (an equal key is not added twice).
  - `ACCEPTED`/`DUPLICATE`/`LATE`/`NOT_FOUND`/`FORBIDDEN` remove the event (`NOT_FOUND`/`FORBIDDEN` with WARN).
  - `429` (honour `Retry-After` up to 3600 s, stored as retry-not-before), `5xx` and transport errors keep the events.
  - Other `4xx` (not 401/408/429) drop the batch with WARN `tracking.event_dropped`.
  - `logout()` clears the queue.
  - One flush at a time across processes: a non-blocking `flock` on `flush.lock`; if it is held, skip.
- No polling, no `BGTaskScheduler`, no silent pushes, no periodic work.
- Network:
  - The base URL must be `https`; `http` is allowed only for hosts `localhost`, `127.0.0.1`, `::1`. Otherwise `configure` throws `ReliablePushError.insecureBaseURL`.
  - `URLSessionConfiguration.ephemeral`; redirects refused (the task delegate answers `nil`); request timeout 10 s.
  - The NSE flush has an overall deadline of `ReliablePushOptions.extensionSendTimeout`, default 5 s.
- Logging:
  - `os.Logger(subsystem: "io.reliablepush", category: "ReliablePush")`, message `event=<name> key=value…`.
  - Names as on Android: `device.registered`, `token.refreshed`, `device.revoked`, `tracking.event_queued`, `tracking.event_sent`, `tracking.event_dropped` (WARN), `tracking.not_registered` (WARN), `auth.failed` (WARN), `rate_limit.exceeded` (WARN), `token.unreadable` (WARN), plus `extension.time_expired` (WARN).
  - WARN maps to `.error`, INFO to `.info`. Below WARN only in `DEBUG` builds of the package.
  - Never log tokens, ID tokens, user ids or notification title/body. `rp_tid` and the device id are logged with `privacy: .public`; nothing else is public.
- Store policy (NFR-5): the SDK ships `PrivacyInfo.xcprivacy`, documented for App Store privacy labels in Task 8:
  - `NSPrivacyTracking` false;
  - collected types Device ID and Product Interaction, linked to the user, purpose App Functionality, not used for tracking;
  - accessed API `NSPrivacyAccessedAPICategoryUserDefaults` with reason `1C8F.1` (App Group).
- E2E is local only, never in CI:
  - `scripts/e2e.sh` stays at `E2E: 17/17 passed`.
  - `scripts/android-e2e.sh` stays at `Android E2E: 8/8 passed, 1 skipped` (the harness changes in Task 5 touch the shared stack).
  - `scripts/ios-e2e.sh` runs the Simulator suite. The Simulator reaches the host directly: backend `http://127.0.0.1:8080`, harness `http://127.0.0.1:8090`, device proxy `http://127.0.0.1:8091` (Task 5).
  - Never restart or resize Colima without the maintainer's OK.
- Versions: Xcode and the Simulator runtime are recorded in Task 0 (`ios/README.md`). The CI runner image and its Xcode are pinned to match (Task 8). Nothing dynamic.
- Credentials (Task 7):
  - The APNs auth key and the team id are created and placed by the maintainer: `harness/build/apns/AuthKey.p8` and `harness/build/apns/apns.properties`, plus `IOS_DEVELOPMENT_TEAM` in the environment.
  - They are git-ignored and never committed, logged, printed or entered by an agent.
  - Without them every build, test and E2E run passes, and the real-APNs entry reports `SKIPPED`.
- Gitflow:
  - All tasks commit to `feature/cycle3-ios-sdk`, cut from `feature/plan-cycle3`.
  - Conventional Commits with scope `ios` / `harness` / `ci` / `docs`.
  - Tick this file's checkboxes in the same commit.
  - The merge line reports `E2E: 17/17 passed`, `Android E2E: 8/8 passed, 1 skipped` and `iOS E2E: N/N passed`.

## Review Focus

1. **Content handler on every path:**
   - Cases: no `rp_tid`, malformed id, not registered, backend hanging past the deadline, `serviceExtensionTimeWillExpire` during the send, a send that finishes after expiry.
   - Expected: the handler is called exactly once, with the original (or customised) content. The notification is never dropped or shown twice.
   - Tests: Task 4 (`contentHandlerCalledOnceWhen…` cases).
2. **Notifications not allowed** (permission denied, or alerts, Notification Center and lock screen all off):
   - Expected: no `DELIVERED`, so the fallback fires.
   - Tests: Task 4 (`deniedSettingsMeanNoDelivered`) and Task 6 (`notificationsBlockedMeansFallback` on the Simulator).
3. **Locked device and unreadable storage:**
   - Cases: Keychain `errSecInteractionNotAllowed` before first unlock; a corrupt item; a restored backup without the ThisDeviceOnly item.
   - Expected: no crash, and the content handler is still called.
     - Locked: the registration is kept, no event is sent now, and the event is lost (the NSE cannot read the token). This is documented as a known limit.
     - Corrupt: the registration is cleared with WARN `token.unreadable`.
     - Restored: the device is not registered, and `register` works.
   - Tests: Task 2.
4. **App and NSE at the same time:**
   - Cases: concurrent enqueue and flush from two `EventQueue` instances on the same directory (two processes in production), and two refreshes.
   - Expected: no event lost or duplicated in the file, and no corrupt JSON.
   - Tests: Task 3 (`twoQueuesOnOneDirectoryKeepEveryEvent`, `flushLockSkipsWhenHeld`).
5. **Unsafe transport:**
   - Cases: an `http://` base URL to a non-local host; a `3xx` from the backend.
   - Expected: the URL is rejected at `configure`; the redirect is not followed, so the token never reaches the target.
   - Tests: Task 1 (`rejectsCleartextToRemoteHost`, `doesNotFollowRedirects`).

---

### Task 0: Swift package, toolchain pins and Xcode prerequisites

**Files:**
- Create:
  - `Package.swift` (repository root)
  - `ios/Sources/ReliablePush/ReliablePush.swift` (version constant only)
  - `ios/Sources/ReliablePush/PrivacyInfo.xcprivacy`
  - `ios/Tests/ReliablePushTests/VersionTests.swift`
  - `ios/README.md`, `ios/.gitignore` (`.build/`, `DerivedData/`, `*.xcresult`, `xcuserdata/`)
  - `ios/scripts/size.sh`
- Modify: root `.gitignore` (`/.build/`, `/.swiftpm/`)

- [x] **Step 1: Check the prerequisites** and record them in the report and in `ios/README.md`:
  - `xcodebuild -version`;
  - `xcrun simctl list runtimes`;
  - the chosen default `IOS_SIMULATOR` device name (an iPhone on the newest installed iOS runtime).

  If `xcodebuild` is missing, stop with **BLOCKED** ("maintainer must install Xcode"). Never install it.
- [x] **Step 2: Manifest.**
  - Products, targets and platforms as in Global Constraints.
  - `resources: [.copy("PrivacyInfo.xcprivacy")]`.
  - Swift settings `.enableUpcomingFeature("ExistentialAny")`.
- [x] **Step 3: Smoke test.**
  - `VersionTests.testVersionIsSnapshot()`: `XCTAssertEqual(ReliablePush.version, "0.1.0-SNAPSHOT")`. `public enum ReliablePush { public static let version = "0.1.0-SNAPSHOT" }` is the only code in this task.
  - Run: `xcodebuild test -scheme ReliablePush -destination "platform=iOS Simulator,name=$IOS_SIMULATOR"` → `** TEST SUCCEEDED **`.
  - `plutil -lint ios/Sources/ReliablePush/PrivacyInfo.xcprivacy` → OK. Its content is as in Global Constraints.
- [x] **Step 4: Size script.** `ios/scripts/size.sh`:
  - Compiles every file under `ios/Sources/ReliablePush` with `xcrun --sdk iphoneos swiftc -emit-library -O -whole-module-optimization -module-name ReliablePush -target arm64-apple-ios15.0 -swift-version 6`.
  - Runs `strip -x` on the result.
  - Prints `ReliablePush stripped arm64 dylib: <bytes> bytes (limit 307200)` and exits 1 above the limit.

  Run it → under the limit.
- [x] **Step 5: Docs.** `ios/README.md`:
  - layout (root manifest, `ios/Sources`, `ios/Tests`, `ios/Sample` from Task 6);
  - prerequisites (Xcode version, Simulator runtime, `IOS_SIMULATOR`);
  - commands.
- [x] **Step 6: Commit** `build(ios): swift package, privacy manifest and size check`

### Task 1: Device API client

**Files:**
- Create: `ios/Sources/ReliablePush/Internal/{DeviceAPI.swift,APIResult.swift,BaseURL.swift,Models.swift}`
- Test:
  - `ios/Tests/ReliablePushTests/{DeviceAPITests.swift,BaseURLTests.swift}`
  - helper `StubURLProtocol.swift`: a `URLProtocol` subclass registered on the test session's configuration, recording requests and serving canned responses.

**Interfaces:**
- Produces:
  - `enum BaseURL { static func validate(_ string: String) throws(ReliablePushError) -> URL }`: normalised, no trailing slash.
  - `public enum ReliablePushError: Error, Sendable { case insecureBaseURL, invalidBaseURL }`
  - `final class DeviceAPI: Sendable`:
    - `init(baseURL: URL, appId: String, userAgent: String, configuration: URLSessionConfiguration = .ephemeral, timeout: TimeInterval = 10)`
    - `func register(idToken: String, deviceId: String?) async -> APIResult<Registration>`
    - `func refresh(token: String) async -> APIResult<IssuedToken>`
    - `func revoke(token: String) async -> APIResult<Void>`
    - `func sendEvents(token: String, events: [QueuedEvent]) async -> APIResult<[EventOutcome]>`
  - Models (`Codable`, `Sendable`):
    - `Registration(deviceId: String, deviceToken: String, expiresAt: Date)`
    - `IssuedToken(deviceToken: String, expiresAt: Date)`
    - `QueuedEvent(trackingId: String, type: EventType /* .delivered | .read, raw "DELIVERED"|"READ" */, occurredAt: Date)`
    - `EventOutcome(trackingId: String, outcome: String)`
  - `enum APIResult<T>` = `.ok(T)` | `.http(status: Int, retryAfterSeconds: Int?)` | `.networkError`.
  - Bodies follow `backend/src/main/resources/openapi.yaml`; `platform` is `IOS`; dates are ISO-8601 with fractional seconds accepted.

- [x] **Step 1: Failing tests:**
  - `testRegisterSendsAppIdIdTokenPlatformAndParsesToken`
  - `testRegisterReusesStoredDeviceId`
  - `testRefreshSendsBearerToken`
  - `testRevokeExpects204`
  - `testSendEventsParsesPerItemOutcomes`
  - `testRetryAfterIsParsedOn429`
  - `testServerErrorAndTimeoutMapToHttpAndNetworkError` (the stub never answers; test-sized timeout 0.5 s)
  - `testDoesNotFollowRedirects` (the stub answers 302 to another URL that records any call: zero calls, result `.http(302)`)
  - `testRejectsCleartextToRemoteHost`: `http://example.com` throws `.insecureBaseURL`; `http://127.0.0.1:8091`, `http://localhost`, `http://[::1]:8080`, `https://x.example` pass.
  - `testBodyIsCappedAt64KB`: a larger response body → `.networkError`.
- [x] **Step 2: Run** the package tests → FAIL. **Step 3: Implement**:
  - A `URLSession` with a delegate whose `willPerformHTTPRedirection` completes with `nil`.
  - Headers: `Content-Type: application/json`, `Accept: application/json, application/problem+json`, the user agent.
  - `JSONEncoder`/`JSONDecoder` with ISO-8601 dates.
- [x] **Step 4: Run** → PASS. **Step 5: Commit** `feat(ios): device api client`

### Task 2: Shared storage and refresh rule

**Files:**
- Create: `ios/Sources/ReliablePush/Internal/{KeychainStore.swift,SharedState.swift,DeviceToken.swift,RefreshGate.swift,TokenStore.swift}`
- Test: `ios/Tests/ReliablePushTests/{DeviceTokenTests.swift,TokenStoreTests.swift,RefreshGateTests.swift,KeychainStoreTests.swift}`

**Interfaces:**
- Produces:
  - `protocol SecretStore: Sendable`:
    - `func read(account: String) -> SecretRead` (`.found(Data)` | `.missing` | `.unavailable` (locked) | `.failed(OSStatus)`)
    - `func write(_ data: Data, account: String) -> Bool`
    - `func delete(account: String)`
  - `final class KeychainStore: SecretStore`:
    - `init(service: String = "io.reliablepush.devicetoken", accessGroup: String?)`
    - accessibility `AfterFirstUnlockThisDeviceOnly`; `write` updates in place or adds.
  - `final class SharedState: Sendable`:
    - `init(defaults: UserDefaults)`
    - typed accessors for `config: (baseURL: URL, appId: String)?`, `deviceId: String?`, `expiresAt: Date?`, `retryNotBefore: Date?`
    - `clearRegistration()`
  - `struct DeviceToken: Sendable`:
    - `(raw: String, issuedAt: Date, expiresAt: Date)`
    - `static func parse(_ raw: String) -> DeviceToken?`
    - `func needsRefresh(now: Date) -> Bool`: remaining < 20 % of `expiresAt − issuedAt`, or expired.
  - `final class TokenStore: Sendable`:
    - `init(secrets: any SecretStore, state: SharedState, appId: String)`
    - `func save(_ r: Registration) -> Bool`
    - `func load() -> TokenLoad` (`.registered(deviceId: String, token: DeviceToken)` | `.notRegistered` | `.unavailable`)
    - `func clear()`
  - `enum RefreshGate { static func tryAcquire() -> Bool }`: true once per process. `resetForTesting()` is `internal`.

- [ ] **Step 1: Failing tests:**
  - `testNeedsRefreshBelowTwentyPercent`: iat 0, exp 100 s → false at 80 s, true at 81 s; expired → true.
  - `testParseRejectsMalformedToken`
  - `testStoreRoundTripsAndKeepsTokenOutOfDefaults`: an in-memory `SecretStore` fake; no defaults value contains the token.
  - `testUndecodableTokenClearsRegistrationWithWarn`
  - `testLockedKeychainKeepsRegistration`: the fake returns `.unavailable` → `.unavailable`, nothing cleared.
  - `testRefreshGateAllowsOncePerProcess`
  - `testKeychainRoundTripWithoutAccessGroup`: the real `KeychainStore(accessGroup: nil)` in the package test host (write, read, update, delete).

  The shared access-group variant needs the sample's entitlements and runs in Task 6 as a hosted test.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(ios): keychain token store, shared state and refresh rule`

### Task 3: Event queue and sender

**Files:**
- Create: `ios/Sources/ReliablePush/Internal/{EventQueue.swift,FileLock.swift,EventSender.swift}`
- Test: `ios/Tests/ReliablePushTests/{EventQueueTests.swift,EventSenderTests.swift}`

**Interfaces:**
- Consumes: `DeviceAPI`, `TokenStore`, `SharedState`, `DeviceToken`, `RefreshGate` (Tasks 1-2).
- Produces:
  - `final class FileLock: Sendable`: `init(url: URL)`, `func withLock<T>(_ body: () throws -> T) rethrows -> T` (blocking `flock(LOCK_EX)`), `func tryLock() -> FileLock.Held?` (non-blocking; `Held` unlocks on `deinit`/`release()`).
  - `final class EventQueue: Sendable`:
    - `init(directory: URL)`
    - `add(_ e: QueuedEvent)`, `peek(max: Int) -> [QueuedEvent]`, `remove(keys: Set<String>)`, `clear()`, `count`
    - every method under `FileLock(events.lock)` plus an in-process lock; it reads the file fresh each time (the other process may have written).
    - The cap is 500.
  - `final class EventSender: Sendable`:
    - `init(api: DeviceAPI, tokens: TokenStore, state: SharedState, queue: EventQueue, flushLock: FileLock, now: @Sendable () -> Date)`
    - `func flush(deadline: Date?) async -> FlushResult` (`.done` | `.retryLater` | `.notRegistered` | `.skipped`)
    - `.skipped` when the flush lock is held, or `now < retryNotBefore`.
    - It refreshes first when `needsRefresh` and the gate allows.
    - Batches of 50 until the queue is empty, a batch fails or the deadline passes.
    - On `401`: one refresh (if the gate allows) and one retry of the batch; a refresh answered `401` clears the registration → `.notRegistered`.

- [ ] **Step 1: Failing tests:**
  - `testQueueSurvivesNewInstance`
  - `testCapDropsOldestWithWarn`
  - `testSameKeyIsNotAddedTwice`
  - `testConcurrentEnqueueKeepsEveryEvent` (8 tasks × 50 adds → 400)
  - `testTwoQueuesOnOneDirectoryKeepEveryEvent` (two instances, 4 threads each, interleaved add/remove → no loss, valid JSON)
  - `testFlushRemovesAcceptedDuplicateLateNotFoundForbidden`
  - `testFlushKeepsEventsOn429And5xxAndNetworkError` (`429` stores retry-not-before = now + `Retry-After`, clamped to 3600)
  - `testFlushSkipsBeforeRetryNotBefore`
  - `testFlushDropsBatchOnOther4xx`
  - `testFlushBatchesFifty` (120 events → 3 calls)
  - `testOn401RefreshesOnceAndRetries`
  - `testRefreshAnswered401ClearsRegistration`
  - `testSecondRefreshInSameProcessIsNotAttempted`
  - `testFlushLockSkipsWhenHeld`
  - `testDeadlineStopsBetweenBatches`
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(ios): cross-process event queue and sender`

### Task 4: Public API, NSE helper and READ

**Files:**
- Create:
  - `ios/Sources/ReliablePush/{ReliablePush.swift (extend),ReliablePushOptions.swift,ReliablePushExtension.swift,RegisterResult.swift}`
  - `ios/Sources/ReliablePush/Internal/{SDK.swift,SDKLog.swift,NotificationGate.swift,TrackingId.swift}`
- Test: `ios/Tests/ReliablePushTests/{ReliablePushTests.swift,ReliablePushExtensionTests.swift,SDKLogTests.swift}`

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces (public API, doc comments on every symbol):
  - `public enum ReliablePush`:
    - `static let version`
    - `static func configure(baseURL: String, appId: String, appGroup: String, options: ReliablePushOptions = .init()) throws(ReliablePushError)`:
      - Call it in `application(_:didFinishLaunchingWithOptions:)` on every launch.
      - The same base URL and app id is a no-op. A different one replaces the stored config and clears registration and queue.
      - It persists the config in the App Group defaults, for the NSE.
      - It starts one flush per launch.
    - `static func register(idToken: String, completion: (@MainActor @Sendable (RegisterResult) -> Void)? = nil)` and `static func register(idToken: String) async -> RegisterResult`: success flushes the queue.
    - `static func trackOpen(response: UNNotificationResponse) -> Bool`:
      - For any action except `UNNotificationDismissActionIdentifier`, it reads `rp_tid` from `response.notification.request.content.userInfo`, persists `READ`, starts a flush, and returns `true`.
      - It returns `false` when the notification is not the SDK's.
      - It delegates to the internal `trackOpen(userInfo:actionIdentifier:)`, which the tests use (`UNNotificationResponse` has no public initialiser).
    - `static func logout(completion: (@MainActor @Sendable (Bool) -> Void)? = nil)`: best-effort revoke, then clear the token, the state and the queue.
    - `static var deviceId: String? { get }`
  - `public struct ReliablePushOptions: Sendable`: `keychainAccessGroup: String? = nil` (nil → the App Group id), `extensionSendTimeout: TimeInterval = 5`.
  - `public enum RegisterResult: Sendable, Equatable`: `.success(deviceId: String)` | `.failed(httpStatus: Int?)`
  - `public final class ReliablePushExtension: @unchecked Sendable` (one instance per NSE instance):
    - `init(appGroup: String, options: ReliablePushOptions = .init())`: reads the config the app stored. Without one, every request is passed through untouched.
    - `func didReceive(_ request: UNNotificationRequest, withContentHandler handler: @escaping @Sendable (UNNotificationContent) -> Void, customize: (@Sendable (UNMutableNotificationContent, String) -> Void)? = nil) -> Bool`:
      - It returns `false` (without calling `handler`) when the request has no valid `rp_tid`, so a host NSE can handle its own pushes.
      - Otherwise it applies `customize`, checks the settings gate, and, if shown and registered, persists `DELIVERED`.
      - It then flushes until the deadline and calls `handler` once.
      - When not registered it calls `handler` with no event (WARN `tracking.not_registered`).
    - `func serviceExtensionTimeWillExpire()`: calls the pending handler immediately with the best content, if not called yet (WARN `extension.time_expired`). The event stays queued.
  - `NotificationGate`: `protocol NotificationSettingsSource { func current() async -> GateSettings }`. The real one reads `UNUserNotificationCenter.current().notificationSettings()`; the tests inject a fake.

- [ ] **Step 1: Failing tests:**
  - `testNotOursReturnsFalseAndDoesNotCallHandler`
  - `testMalformedRpTidReturnsFalseWithWarn`
  - `testDeliveredIsPersistedBeforeHandlerAndSent`
  - `testDeniedSettingsMeanNoDelivered`
  - `testAllPresentationsOffMeanNoDelivered`
  - `testProvisionalCountsAsShown`
  - `testNotRegisteredCallsHandlerWithoutEvent`
  - `testContentHandlerCalledOnceWhenBackendHangs` (deadline 0.5 s)
  - `testContentHandlerCalledOnceWhenTimeExpiresDuringSend` (expiry, then the send completes: still exactly one call)
  - `testCustomizerRunsBeforeHandler`
  - `testTrackOpenSendsReadForTapAndCustomActionNotForDismiss`
  - `testRegisterStoresTokenAndCallsBackOnMain`
  - `testLogoutRevokesAndClearsQueueAndState`
  - `testConfigureRejectsCleartextRemoteURL`
  - `testConfigureWithOtherAppIdClearsRegistration`
  - `testReleaseLoggingOnlyWarnAndAbove` (`SDKLog` with `debug: false` emits nothing below WARN; a test log sink)
  - `testNoTokenOrContentInLogs`
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.** `SDK` is the composition root, built at `configure` or by the extension. Concurrency: actors or locks for internal state, no global mutable state outside `SDK` and `RefreshGate`, Swift 6 clean with no warnings.
- [ ] **Step 4: Run** → PASS, and `ios/scripts/size.sh` passes.
- [ ] **Step 5: Commit** `feat(ios): public api, notification service extension helper and read tracking`

### Task 5: Harness — device-traffic proxy and iOS preview

**Files:**
- Create:
  - `harness/src/main/java/io/reliablepush/harness/devices/DeviceProxy.java`
  - `harness/src/test/java/io/reliablepush/harness/devices/DeviceProxyTest.java`
- Modify:
  - `ConsoleApiController.java` (`POST /console/api/device-network`, `GET` state)
  - `PayloadPreview.java` (`iosDebugOpenUrl`)
  - `harness/compose/docker-compose.yml` (publish `127.0.0.1:8091:8091`; `RP_DEVICE_PROXY_TARGET=http://api:8080`)
  - `HarnessProperties.java`
  - the console static files (an online/offline switch in Devices, and the iOS debug line in the payload preview)
  - `PayloadPreviewTest`, `docs/testing.md` (console section)

**Interfaces:**
- Produces:
  - `DeviceProxy`: a TCP proxy (virtual threads, blocking sockets) listening on `0.0.0.0:8091` inside the container and forwarding to `api:8080`.
    - `setOnline(boolean)`: offline closes every open connection and refuses new ones (accept, then close at once), so clients see a transport error, not an HTTP status.
    - It starts online.
    - It never logs payloads.
  - `POST /console/api/device-network {online: boolean}` → `200 {online}`; `GET /console/api/device-network` → `{online}`. The existing `ConsoleRequestGuard` applies.
  - `PayloadPreview.iosDebugOpenUrl(tid, title, body)`: `xcrun simctl openurl booted 'rpsample://debug-push?rp_tid=…&title=…&body=…'`, with percent-encoded values and the whole URL single-quoted for the shell. It is shown in the console preview next to the APNs JSON.

- [x] **Step 1: Failing tests:**
  - `forwardsRequestAndResponse` (a JDK `HttpServer` as target)
  - `offlineRefusesAndClosesOpenConnections`
  - `backOnlineForwardsAgain`
  - `deviceNetworkEndpointTogglesProxy` (IT)
  - `iosDebugOpenUrlEncodesAndQuotes` (title with a quote, an ampersand and a space)
- [x] **Step 2: Run** `./mvnw -B -pl harness -am verify` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
  - `./scripts/e2e.sh` → `E2E: 17/17 passed`
  - `./scripts/android-e2e.sh` → `Android E2E: 8/8 passed, 1 skipped`
- [x] **Step 5: Commit** `feat(harness): device traffic proxy with offline switch and ios preview`

### Task 6: Sample app and Simulator E2E

**Files:**
- Create:
  - `ios/Sample/ReliablePushSample.xcodeproj` (committed, hand-maintained, file-system-synchronised groups, local package reference to the repository root; shared schemes `ReliablePushSample` and `ReliablePushSampleE2E`)
  - `ios/Sample/Config/{Debug.xcconfig,Release.xcconfig}` (`RP_BUNDLE_ID` default `io.reliablepush.sample`, `RP_APP_GROUP` default `group.io.reliablepush.sample`)
  - `ios/Sample/ReliablePushSample/{SampleApp.swift,AppDelegate.swift,ContentView.swift,HarnessClient.swift (DEBUG only),DebugPush.swift (DEBUG only),Info-Debug.plist,Info-Release.plist,ReliablePushSample.entitlements}`
  - `ios/Sample/ReliablePushSampleNSE/{NotificationService.swift,Info.plist,ReliablePushSampleNSE.entitlements}`
  - `ios/Sample/ReliablePushSampleTests/SharedStorageTests.swift` (hosted in the app)
  - `ios/Sample/ReliablePushSampleUITests/{IOSE2ETests.swift,NotificationsBlockedUITests.swift,Harness.swift}`
  - `scripts/ios-e2e.sh`, `scripts/lib/IosE2eSummary.java`
- Modify: `scripts/lib/common.sh` (shared helpers only; no behaviour change for `e2e.sh` and `android-e2e.sh`)

**Interfaces:**
- Consumes: the public API (Task 4), the harness endpoints (`/console/api/grants`, `/real-devices`, `/push`, `/notifications/{id}`, `/logs`, `/device-network` from Task 5).
- Produces:
  - Sample app:
    - Both targets have the App Group entitlement `$(RP_APP_GROUP)`.
    - `AppDelegate` calls `configure` on launch and is the `UNUserNotificationCenterDelegate`: `didReceive` → `ReliablePush.trackOpen(response:)`; `willPresent` → `[.banner, .list]`.
  - Debug build:
    - The base URL, app id and harness URL come from the launch environment (`RP_BASE_URL`, default `http://127.0.0.1:8091`; `RP_APP_ID`, default `shop`; `RP_HARNESS_URL`, default `http://127.0.0.1:8090`).
    - `RP_RESET=1` clears the Keychain item, the App Group state and the queue before `configure`.
    - `Info-Debug.plist` sets `NSAppTransportSecurity.NSAllowsLocalNetworking = YES`.
    - `ContentView` has Register (harness grant → `register` → `POST /console/api/real-devices` with platform `IOS`), Logout and the deviceId label, with accessibility identifiers `register`, `logout`, `deviceId`. It asks for notification authorization on first launch.
    - `DebugPush` handles `rpsample://debug-push?rp_tid=&title=&body=`. It builds a `UNNotificationRequest` with APNs-shaped `userInfo` (`aps` + top-level `rp_tid`) and runs `ReliablePushExtension.didReceive` in-process, the same code path as the NSE. It then adds the handler's content as a local notification (identifier = `rp_tid`), so the UI test can tap it.
  - Release build: no URL scheme handling, no harness client, no ATS exception.
  - NSE: `NotificationService` delegates to `ReliablePushExtension` (about 15 lines) and passes the content through when the helper returns `false`.
  - `./scripts/ios-e2e.sh [--keep] [--test <name>]`:
    1. Checks `xcodebuild`, `xcrun simctl`, JDK 21, Docker.
    2. Starts the harness stack (project `reliable-push-ios-e2e`, fast clock, `rp_start_stack`).
    3. Boots `IOS_SIMULATOR` unless one is booted (`simctl bootstatus -b`), and sets the language to en-US for alert button labels.
    4. Phase "blocked": `simctl uninstall`, then `xcodebuild test -only-testing:ReliablePushSampleUITests/NotificationsBlockedUITests`. It taps "Don't Allow".
    5. Phase "main": uninstall, then `xcodebuild test -scheme ReliablePushSampleE2E` (the hosted tests plus `IOSE2ETests`). It taps "Allow" once.
    6. Each phase writes its own `.xcresult` under `harness/build/ios-e2e/`.
    7. `IosE2eSummary.java` reads `xcrun xcresulttool get test-results tests --path <bundle>` (JSON) and writes `summary.json` (the shape of `e2e.sh`) and JUnit XML.
    8. Collects the container logs and tears down. It shuts the Simulator down only if it booted it.
    9. Prints `iOS E2E: N/N passed[, M skipped]`; exit 0 means all passed.

- [ ] **Step 1: Tests.** Each UI test creates its own user id, launches the app with `RP_RESET=1`, taps Register and waits for the real-device entry. It asks the harness to send (`POST /console/api/push`), opens the debug URL with `XCUIApplication.open(_:)` (iOS 16.4+; the Simulator runtime is newer), and polls `GET /console/api/notifications/{id}`. Each test relaunches the app, so it has a fresh process and the refresh gate is reset.
  - `testDeliveredIsReported`: NOT_DELIVERED, timeout 5 → `DELIVERED`, inbox empty after the timeout.
  - `testReadIsReportedOnTap`: NOT_READ → home button, tap the banner (Springboard) → `READ`.
  - `testOfflineDeliveryIsSentOnNextLaunch`: device network offline (`/device-network`), deliver, still `PENDING` after 3 s, online, terminate and relaunch the app (no reset) → `DELIVERED` before the timeout (timeout 60).
  - `NotificationsBlockedUITests.testNotificationsBlockedMeansFallback`: authorization denied → no `DELIVERED`, `FALLBACK_TRIGGERED` with one webhook.
  - `testTokenRefreshNearExpiry`: app `news` (validity 20 s), wait 17 s, deliver → `DELIVERED`, and the backend log has `device.token_refreshed` for the device id.
  - `testLogoutRevokesDevice`: logout, then a push → no `DELIVERED`, the fallback fires.
  - Hosted `SharedStorageTests.testKeychainAccessGroupAndAppGroupAreUsable`: a `KeychainStore` with access group `$(RP_APP_GROUP)` and the App Group container round-trip without `errSecMissingEntitlement`, which proves the entitlements the NSE relies on.
- [ ] **Step 2: Run** `./scripts/ios-e2e.sh` before the sample exists → FAIL. **Step 3: Implement** the sample, the project and the script. Validate the project with `xcodebuild -list -project ios/Sample/ReliablePushSample.xcodeproj`.
- [ ] **Step 4: Run** → `iOS E2E: 7/7 passed` (6 UI entries + the hosted storage test). Feedback loop as in `run-e2e`: at most 3 rounds, then stop and record.
- [ ] **Step 5: Commit** `feat(ios): sample app and simulator e2e against the harness`

### Task 7: Real APNs pushes (optional, maintainer's Apple Developer team)

Why: Task 6 proves the SDK logic but not the real entry, where APNs makes the OS start the NSE process, also with the app killed, and the NSE reads the token the app stored through the shared Keychain group and App Group. On Apple-silicon Macs the Simulator receives real APNs sandbox pushes and runs the NSE. That needs an app signed by a team with the Push Notifications capability (paid Apple Developer Program), a bundle id registered by that team, and an APNs auth key. No build or test depends on these.

**Files:**
- Create:
  - `harness/src/main/java/io/reliablepush/harness/apns/{ApnsCredentials.java,ApnsProviderToken.java,ApnsSender.java,ApnsResult.java,ApnsPusher.java}`
  - `harness/src/test/java/io/reliablepush/harness/apns/{ApnsProviderTokenTest.java,ApnsSenderTest.java}` (a stub HTTPS/HTTP endpoint, with a test EC P-256 key generated in the test)
  - `ios/Sample/ReliablePushSample/ReliablePushSample-APNs.entitlements` (adds `aps-environment = development`)
- Modify:
  - `RealDeviceRegistry` (an optional `apnsToken`, compare-and-clear like `fcmToken`)
  - `ConsoleApiController` (`POST /console/api/real-devices/{id}/apns-token`; `GET /console/api/apps` reports `apnsEnabled`; `/push` also sends through APNs)
  - `harness/compose/docker-compose.yml` (mount `../build/apns:/run/apns:ro`, created empty by the scripts)
  - the sample (Debug + `RP_APNS=1`: register for remote notifications, report the hex token to the harness)
  - `Debug.xcconfig` (`CODE_SIGN_ENTITLEMENTS` switches to the APNs file when `RP_APNS=1`; `DEVELOPMENT_TEAM = $(IOS_DEVELOPMENT_TEAM)`)
  - `scripts/ios-e2e.sh`, `.gitignore` (`harness/build/apns/`), `docs/testing.md`

**Interfaces:**
- Consumes: `PayloadPreview.apns` (the exact payload, spec §6), `RealDeviceRegistry`, the FCM pusher pattern (Task 7 of plan 2).
- Produces:
  - `ApnsCredentials.load(Path dir)`:
    - It reads `AuthKey.p8` (PKCS#8 EC P-256 PEM) and `apns.properties` (`keyId`, `teamId`, `topic` = the sample bundle id, `environment` = `sandbox`).
    - `toString()` shows only `keyId`, `topic` and `environment`.
    - A missing or incomplete directory → `Optional.empty()`, and real mode is off.
  - `ApnsProviderToken`: an ES256 JWT (`alg` ES256, `kid` = keyId; `iss` = teamId, `iat`), with the JOSE signature converted from DER to raw R‖S (64 bytes). It is cached for 50 minutes; Apple rejects tokens older than 1 h and refreshes more often than every 20 min.
  - `ApnsSender.send(deviceToken, trackingId, title, body): ApnsResult`:
    - `POST https://api.sandbox.push.apple.com/3/device/<token>` over HTTP/2 (`java.net.http`).
    - Headers: `authorization: bearer <jwt>`, `apns-topic`, `apns-push-type: alert`, `apns-priority: 10`, `apns-expiration: 0`. Body = `PayloadPreview.apns`.
    - Results: `200` → `SENT`; `410`, or `400` with `BadDeviceToken` → `UNREGISTERED` (the token is dropped); anything else → `FAILED(status, reason)`.
    - The host is fixed; a package-private constructor overrides it for tests.
  - Tokens (APNs device token, provider JWT, key) are never logged or returned by any endpoint (only "token present").
  - `ios-e2e.sh` adds entry `apns-real-delivery` when `harness/build/apns/AuthKey.p8`, `apns.properties` and `IOS_DEVELOPMENT_TEAM` all exist:
    1. Build with `RP_APNS=1`, then register (`RP_RESET=1`) and wait for the APNs token at the harness.
    2. Terminate the app (`simctl terminate`).
    3. Send a console push → `DELIVERED` within 60 s through the real NSE.

    Otherwise the entry is `SKIPPED` and the line reads `iOS E2E: 7/7 passed, 1 skipped`.

- [ ] **Step 1: Failing tests:**
  - `providerTokenIsEs256WithRawSignature` (verify with the test public key after converting back to DER; header and claims as above)
  - `providerTokenIsCachedFor50Minutes`
  - `sendPostsAlertPushWithHeaders` (the body equals the preview)
  - `goneOrBadTokenDropsTheToken`
  - `credentialsNeverInToStringOrLogs`
  - `missingCredentialsDisableRealMode`
- [ ] **Step 2: Run** `./mvnw -B -pl harness -am verify` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
  - The sample builds without a team: `xcodebuild build` (Debug, Simulator, `RP_APNS` unset) → OK.
  - `./scripts/ios-e2e.sh` → `7/7 passed, 1 skipped`.
- [ ] **Step 5: Docs** (`docs/testing.md`, "Real APNs pushes"). The maintainer:
  1. registers the bundle id (or a variant set through `RP_BUNDLE_ID`) and the App Group with Push Notifications;
  2. creates an APNs auth key and saves it as `harness/build/apns/AuthKey.p8` (`chmod 600`);
  3. writes `apns.properties`;
  4. exports `IOS_DEVELOPMENT_TEAM`;
  5. revokes the key when done.

  Everything stays git-ignored.
- [ ] **Step 6: Commit** `feat(harness): optional real apns sender for the sample app`
- [ ] **Step 7 (maintainer, optional):** with the files in place, run `./scripts/ios-e2e.sh --test apns-real-delivery` → PASSED. Record the result (or "not run, no credentials") in `docs/STATUS.md`. The agent never creates keys, signs in or enters credentials.

### Task 8: CI, docs and final verification

**Files:**
- Create: `.github/workflows/ios-ci.yml`, `docs/ios.md`
- Modify:
  - `.github/workflows/codeql.yml` (Swift job)
  - `docs/integration-guide.md` (§4 Mobile apps → iOS), `README.md`, `AGENTS.md`, `docs/testing.md`, `.agents/skills/run-e2e/SKILL.md`
  - `docs/specs/2026-11-03-reliable-push-design.md` ("Changes during implementation (plan 3)", §12), `docs/STATUS.md`

- [ ] **Step 1: CI.** Workflow `ios-ci.yml`:
  - Triggers like `android-ci.yml`; paths `Package.swift`, `ios/**` and the workflow file. `permissions: contents: read`; actions pinned by SHA.
  - Runs on the GitHub macOS image whose Xcode major version matches Task 0, with `xcode-select` to that exact Xcode.
  - `xcodebuild test` of the package on a Simulator of that image.
  - `xcodebuild build` of `ReliablePushSample` (Debug and Release, `generic/platform=iOS Simulator`, `CODE_SIGNING_ALLOWED=NO`).
  - `ios/scripts/size.sh`; `plutil -lint` of the privacy manifest.
  - Uploads the `.xcresult` on failure.
  - No UI tests, no harness, no credentials.
- [ ] **Step 2: Security.**
  - CodeQL gains a Swift job (`macos` runner, manual build `xcodebuild build -scheme ReliablePush -destination generic/platform=iOS CODE_SIGNING_ALLOWED=NO`, paths as above).
  - Dependabot: no change (the package has no dependencies; the GitHub Actions entry already covers the workflows).
  - Validate with `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12` → no errors.
- [ ] **Step 3: Docs.** `docs/ios.md` covers:
  - adding the package by URL;
  - the App Group (and why the Keychain group defaults to it);
  - the NSE target with the helper snippet;
  - `configure` in `didFinishLaunching`;
  - `trackOpen` in `UNUserNotificationCenterDelegate`;
  - asking for authorization;
  - the payload contract (`alert` + `mutable-content: 1`, top-level `rp_tid`, no silent pushes);
  - retry timing (next launch or next NSE run);
  - the logging names;
  - App Store privacy labels (Device ID and Product Interaction, linked to the user, App Functionality, not tracking) and the shipped `PrivacyInfo.xcprivacy`;
  - known limits:
    - notifications disabled → fallback;
    - a push arriving before the first unlock after a reboot cannot be reported;
    - an NSE killed by the system before the send → reported at the next launch or NSE run;
    - a payload without `mutable-content` is not tracked → fallback.

  Also update:
  - integration guide §4 (points to the guide);
  - the README components table;
  - `AGENTS.md` commands (package tests, size check, `./scripts/ios-e2e.sh`);
  - the `docs/testing.md` iOS section;
  - the run-e2e skill (the iOS suite);
  - the spec changes block for plan 3 (root `Package.swift`; Keychain group = App Group by default; locked-device rule; debug URL instead of `simctl push`, since the Simulator does not run NSEs for simulated pushes; device proxy; optional APNs sender; size gate measurement).
- [ ] **Step 4: Final verification**, each command run once:
  - package tests on the Simulator, and `ios/scripts/size.sh`
  - `./mvnw -B -pl harness -am verify`
  - `cd backend && ./mvnw -B verify` (unchanged; a regression guard)
  - `cd android && ./gradlew build`
  - `./scripts/e2e.sh` → `E2E: 17/17 passed`
  - `./scripts/android-e2e.sh` → `Android E2E: 8/8 passed, 1 skipped`
  - `./scripts/ios-e2e.sh` → `iOS E2E: 7/7 passed, 1 skipped` (or `apns-real-delivery` PASSED with the maintainer's files)

  Record the counts and the three lines in `docs/STATUS.md`.
- [ ] **Step 5: Commit** `ci(ios): package tests, sample build, size check and codeql; docs: ios sdk`, then run the `session-handoff` skill.
