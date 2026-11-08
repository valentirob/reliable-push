# reliable-push Android SDK

Kotlin SDK (`io.reliablepush:reliable-push-android`, minSdk 26, compileSdk 37). Own Gradle build, not part of the Maven reactor.

## Layout

| Path | What |
|---|---|
| `reliablepush/` | The library (package `io.reliablepush.android`, `explicitApi()`) |
| `sample/` | Sample app: login (`ensureRegistered` at start and after login) / Logout against the local harness (debug build), FCM entry point, emulator E2E tests |
| `gradle/libs.versions.toml` | Every pinned version, with release dates |
| `gradle.properties` | `VERSION_NAME`, the SDK version |

## Public API (details: [docs/android.md](../docs/android.md))

| Call | Where | What |
|---|---|---|
| `ReliablePush.configure(context, baseUrl, appId, options)` | `Application.onCreate`, every launch | stores the configuration; sends events left from earlier launches |
| `ReliablePush.ensureRegistered(idTokenProvider) { result -> }` | app start or foreground, and right after login | the only place the device token is renewed: registers, refreshes, or answers `AlreadyRegistered` without network |
| `ReliablePush.register(idToken) { result -> }` | optional | always registers |
| `ReliablePushMessaging.onMessageReceived(this, message)` / `ReliablePush.handle(context, data)` | `FirebaseMessagingService` | data-only FCM message: gate, persist `DELIVERED`, post the notification, send; never throws, posts a minimal notification if anything fails |
| `ReliablePush.trackOpen(intent)` | launch activity `onCreate` and `onNewIntent` | reports `READ` |
| `ReliablePush.logout()` | sign-out, user switch | revokes and clears the registration and the queue |

The receive path, the event flush and the retry job never renew the device token (spec
[§3.1](../docs/specs/2026-11-08-sidecar-integration-revision-design.md)).

## Prerequisites

- JDK 21 (`JAVA_HOME`)
- Android SDK with platform `android-37` (`ANDROID_HOME`, default `$HOME/Library/Android/sdk`)
- No global Gradle: use `./gradlew` (the wrapper verifies the distribution checksum)

## Commands

| Task | Command |
|---|---|
| Lint, unit tests, debug and release AAR | `./gradlew build` |
| Unit tests only | `./gradlew :reliablepush:testDebugUnitTest` |
| Emulator E2E against the harness stack (local only, from the repo root; boots the AVD if no device is attached) | `./scripts/android-e2e.sh [--test <method>] [--keep]` |
| Runtime dependencies of the library | `./gradlew :reliablepush:dependencies --configuration releaseRuntimeClasspath` |

Robolectric tests run on JDK 21 with `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` (set in the build).

The sample's debug build talks to `http://10.0.2.2:8080` (backend) and `http://10.0.2.2:8090` (harness), the emulator's
view of the host (Gradle properties `rp.baseUrl`, `rp.appId`, `rp.harnessUrl`). Cleartext is allowed only to `10.0.2.2`,
and only in the debug build. The release build uses `rp.releaseBaseUrl` (default `https://reliable-push.example.invalid`,
a placeholder that never resolves): set it to your backend's `https` URL.
On Android 17 that private address also needs the runtime permission `ACCESS_LOCAL_NETWORK` (local network protection),
declared by the debug build and granted by the E2E (`pm grant` / `adb install -g`).
