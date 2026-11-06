# Testing

The strategy is in spec section 10. This page covers what exists for the backend today and how to run it.

## Prerequisites

- JDK 21 and Docker (Testcontainers starts PostgreSQL 16; the image tests build and run the container image).
- Use the Maven wrapper from `backend/`. With Colima or another non-default Docker socket, export the usual variables
  first, for example `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

## Commands

| Goal | Command (run in `backend/`) |
|---|---|
| Everything the CI build runs: unit tests, integration tests, JaCoCo gate | `./mvnw -B verify` |
| Container image build + image tests only | `./mvnw -B verify -Pimage` |
| Unit tests only | `./mvnw -B test` |
| One unit test class or method | `./mvnw -B test -Dtest=FallbackDecisionTest` / `-Dtest=FallbackDecisionTest#methodName` |
| One integration test class | `./mvnw -B verify -Dit.test=NotificationApiIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` |

Reports: `target/surefire-reports/` (unit), `target/failsafe-reports/` (integration), coverage in
`target/site/jacoco/index.html` (and `jacoco.csv`). The build fails if the line coverage of the bundle drops below 80%
(`jacoco:check`); the current figure is recorded in [STATUS.md](STATUS.md).

## Test levels

| Level | Naming / tooling | Runs in | Covers |
|---|---|---|---|
| Unit | `*Test`, JUnit 5 + AssertJ, Mockito as a Java agent | surefire (`test`) | Pure logic: `FallbackDecision` (trigger window, retry and backoff rules), `WebhookSigner`, device token issue and strict verification, signing-key parsing, identity verifiers (`SignedGrantVerifier`, URL rules), rate limiter, `TimeScale`, secret references, configuration validation, `EventLog` and the ECS template, `RpMetrics` |
| Integration | `*IT`, extends `PostgresTestBase` (the full application on a random port against a real PostgreSQL 16 Testcontainer, no database mocking), WireMock for webhook receivers, `FakeOidc` for an OIDC issuer and JWKS | failsafe (`integration-test`) | REST endpoints (`NotificationApiIT`, `DeviceApiIT`, `EventsApiIT`, `SenderApiKeyIT`, `ProblemHandlerIT`), schema and constraints (`SchemaIT`), the fallback worker with concurrent claiming, leases, re-claim and fenced outcomes (`FallbackSchedulerIT`), the signed webhook client against WireMock (`HttpWebhookClientIT`), retention (`RetentionJobIT`), access log and JSON output (`AccessLogIT`, `JsonLogOutputIT`), metrics (`MetricsIT`), OIDC verification and JWKS caching (`OidcIdTokenVerifierIT`), application startup (`ApplicationStartsIT`), the ops dashboard API (`AdminApiIT`, `AdminOverviewIT`, `AdminSearchIT`, `AdminRateLimitIT`, `AdminDisabledIT`: no admin key means `404`), the nightly rollup (`DailyRollupIT`) and the webhook attempt rows (`FallbackAttemptIT`) |
| Contract | `OpenApiContractTest` and `OpenApiAssert` (swagger-request-validator, test scope) | surefire + the ITs | Real responses are validated against `backend/src/main/resources/openapi.yaml`, so the contract and the implementation cannot drift |
| Container image | `ContainerImageIT`, tagged `image` | failsafe, only with `-Pimage` | Starts as an arbitrary non-root UID, stays below the 250 MB budget (sum of uncompressed layers), read-only root filesystem with a writable `/tmp`, JSON-only stdout |
| Logging assertions | inside the ITs and `EcsTemplateTest` | both | Catalogue events are emitted, fields are unique, and no secrets, tokens, user ids or message content reach the log |
| Client | `client-java/`, JUnit 5 against a local HTTP server | surefire | The Java sender client: requests, problem details, timeouts, redirects, `Retry-After`; an enforcer rule keeps it free of runtime dependencies |
| Harness | `harness/`, JUnit 5, `*Test` and `*IT` (Spring on a random port, no Docker needed) | surefire / failsafe | The harness itself: device simulator, signed grants, fallback receiver, fake sender, tracking poller, scenario runner, console REST and SSE |
| End-to-end | `harness/`, `./scripts/e2e.sh` | local only, never in CI | 17 scenarios across the real backend (one API, two workers), PostgreSQL, the fallback receiver and the device simulator, with the fast clock |
| Console smoke | Playwright for Java against the harness web console | local only | The 18th entry of the E2E run: `ConsoleSmokeE2E` (a headless browser registers a device, sends a push and sees `DELIVERED` and the fallback in the inbox) and `AdminSmokeE2E` (signs in to `/admin/` with the per-run admin key, checks the overview, a search and a notification detail) |
| Load | `loadtest/`, Gatling (Java DSL), `./scripts/load.sh` | local only, never in CI (CI compiles the simulations and runs the module's unit tests) | NFR-2 ingest (`EventIngestSimulation`) and the fallback burst drained by the workers (`FallbackBurstSimulation` + `BurstDrainProbe`) |
| Helm chart | helm-unittest, `helm lint --strict`, kubeconform (`deploy/helm/scripts/validate.sh`); kind smoke install (`./scripts/helm-smoke.sh`) | validate: CI and local; kind: local only | Required values and secret rules, rendering per mode and platform, schemas; a real install with a worker rollout during dispatch |
| Mobile | Robolectric and emulator tests (Android), XCTest unit tests and Simulator UI tests (iOS) | unit tests: CI (`android-ci.yml`, `ios-ci.yml`); emulator and Simulator suites: local only | See the Android and iOS sections below |

## Notes on how the suites work

- **Default build vs. image tests:** `-Pimage` flips the failsafe groups: the default build excludes tests tagged `image`,
  the `image` profile runs only those (after `docker build` in `pre-integration-test`) and skips the JaCoCo gate. CI runs
  `./mvnw -B verify -Pimage -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false` so surefire does not repeat the
  unit tests.
- **One shared database:** all ITs share a JVM-wide PostgreSQL container; tests create their own users and tracking ids
  instead of cleaning tables, so they can run in any order.
- **Test configuration:** `src/test/resources/application-test.yml` (profile `test`) holds throwaway apps and obviously fake
  secrets; the device signing keys are generated per JVM and never written to disk.
- **Time in tests:** the worker ITs use a controllable `MutableClock` and the fast clock (1 product minute = 1 s) instead of waiting for real minutes.
- **Clean output:** tests are expected to finish without stray stack traces or WARN lines from the code under test.

## Client and harness builds

Run from the repository root (one Maven wrapper for `client-java` and `harness`; the backend keeps `backend/mvnw`):

| Goal | Command |
|---|---|
| Client build and tests (what CI runs) | `./mvnw -B -pl client-java verify` |
| Harness build and tests, including `client-java` (what CI runs) | `./mvnw -B -pl harness -am verify` |
| Console smoke test against a running stack (needs Playwright's Chromium, about 150 MB, downloaded on first use) | `./mvnw -B -pl harness -am verify -Pconsole-smoke` |
| Loadtest module: compiles the simulations, runs the unit tests, never starts Gatling (what CI runs) | `./mvnw -B -pl loadtest verify` |

The default build never downloads Playwright. GitHub Actions runs the client, harness and loadtest builds
(`.github/workflows/harness-ci.yml`); it never runs the stack or a load test.

## End-to-end tests (local only)

`./scripts/e2e.sh [--scenario <name>] [--keep]` (Windows, PowerShell 7: `pwsh scripts/e2e.ps1 [-Scenario <name>] [-Keep]`; the
PowerShell scripts have not been run on Windows). Needs Docker with the compose plugin, `curl`, and ports 8080, 8090 and 8091 free
on 127.0.0.1. The images build inside Docker, but `console-smoke` (so the full 18-entry run) calls the host `./mvnw`: it needs
JDK 21 and network access (Maven artifacts, Playwright's Chromium). With Colima: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.

- **Stack:** `harness/compose/docker-compose.yml`: PostgreSQL, one API, two workers (`worker-a`, `worker-b`) and the
  harness. A one-shot container generates new secrets for every run into `harness/build/e2e/secrets.env` (git-ignored).
  Devices register through the backend's `signed-grant` identity (the harness signs the grants).
- **Fast clock:** 1 product minute = 1 s, worker poll 500 ms, lease 12 minutes (= 12 s). The whole run takes about 5 minutes.
- **Scenarios** (17, plus `console-smoke`): `delivered-before-timeout`, `read-policy-delivered-only`, `read-policy-read`,
  `not-delivered-single-fallback`, `late-delivery-after-fallback`, `multi-device`, `cancellation`,
  `token-refresh-near-expiry`, `rate-limited`, `receiver-retries-then-succeeds`, `receiver-final-failure`,
  `retries-exhausted-fails`, `worker-crash-inside-window`, `worker-crash-outside-window`, `log-catalogue`, `log-hygiene`, `admin-dashboard`.
  `admin-dashboard` registers notifications, lets the simulated device confirm some and the others fall back (the receiver answers
  500 once, then 200), then checks `/v1/admin/overview`, the search and the detail with its events and attempts. The per-run admin
  key (`HARNESS_ADMINKEY`) is generated next to the other secrets and never printed. `console-smoke` runs `ConsoleSmokeE2E` and
  `AdminSmokeE2E`.
- **Worker-crash scenarios** are driven by the script, not the harness container: it stops `worker-b`, asks the harness to
  `prepare`, waits until the webhook call is in flight, kills `worker-a`, waits (inside or outside the trigger window), starts
  both workers and asks the harness to `verify`.
- **Results:** exit code 0 only if all 18 entries PASSED. `harness/build/e2e/summary.json`
  (`{scenario, status, durationMs, failure: {expected, actual, hint}, logs}`), JUnit XML in `harness/build/e2e/junit/`,
  container logs and the backend JSON logs in `harness/build/e2e/logs/`. Logs are collected and the stack is torn down even
  when scenarios fail (unless `--keep`).
- **Failures:** `grep -B2 -A6 '"FAILED"' harness/build/e2e/summary.json`, then search the logs by `trace.id` or
  `rp.tracking_id`.
- **Safety checks:** the script refuses to start if port 8080, 8090 or 8091 is taken (it names the port) and if a stack of an earlier
  `--keep` run exists it removes it first. If a service is not ready within `E2E_READY_TIMEOUT` seconds (default 120) it names
  the container and prints its last log lines.
- The agent workflow is in [`.agents/skills/run-e2e/SKILL.md`](../.agents/skills/run-e2e/SKILL.md).

## Android SDK tests

Needs JDK 21 and the Android SDK (`ANDROID_HOME`; the build downloads missing packages). No Docker for the unit tests.

| Goal | Command |
|---|---|
| Lint, unit tests (Robolectric, JUnit 4), debug and release AAR; what CI runs | `cd android && ./gradlew build` |
| Unit tests only | `cd android && ./gradlew :reliablepush:testDebugUnitTest` |
| Release AAR size (CI gate: under 102,400 bytes) | `ls -l android/reliablepush/build/outputs/aar/reliablepush-release.aar` |
| Emulator E2E (local only) | `./scripts/android-e2e.sh [--test <method>] [--release-lib] [--keep]` |
| Install into `~/.m2` (unsigned; signing only when `SIGNING_KEY` and `SIGNING_PASSWORD` are set) | `cd android && ./gradlew :reliablepush:publishToMavenLocal` |

The release build is minified with R8 (`android/reliablepush/proguard-rules.pro` keeps the public API in
`io.reliablepush.android` and the manifest's `RetryJobService`, moves internal classes into
`io.reliablepush.android.internal` and does not widen member access); the unit tests and the default E2E run on the debug variant.
`./scripts/android-e2e.sh --release-lib` runs the same emulator suite with the sample consuming the minified release AAR
(installed into `~/.m2` by `publishToMavenLocal`), so run it after changing the keep rules.

`./scripts/android-e2e.sh` starts the harness compose stack, boots the AVD `Medium_Phone_API_37.0` (override with
`ANDROID_AVD`; it is stopped again if the script started it), runs the sample's instrumented tests and the library's
Keystore tests, and writes `harness/build/android-e2e/` (`summary.json`, `junit/`, `logs/`); it ends with `Android E2E: 8/8 passed, 1 skipped`
(the skipped entry is `fcm-real-delivery`, see the next section). The emulator reaches the stack at `10.0.2.2`
(`8080` backend, `8090` harness). On Android 17 the E2E grants the sample `ACCESS_LOCAL_NETWORK`. The workflow
`.github/workflows/android-ci.yml` never runs it: CI has no emulator and no Firebase files.

## iOS SDK tests

Need Xcode 26.6 selected with `xcode-select` and an iOS Simulator (iPhone 17); the Command Line Tools alone have no XCTest.
Versions are pinned in `ios/README.md`. No Docker for the unit tests.

| What | Command |
|---|---|
| Package unit tests (XCTest; what CI runs) | `xcodebuild test -scheme ReliablePush -destination "platform=iOS Simulator,name=${IOS_SIMULATOR:-iPhone 17}"` |
| Binary size (CI gate: stripped arm64 dylib at `-O` under 307,200 bytes) | `ios/scripts/size.sh` |
| Privacy manifest lint | `plutil -lint ios/Sources/ReliablePush/PrivacyInfo.xcprivacy` |
| Simulator E2E (local only) | `./scripts/ios-e2e.sh [--test <name>] [--keep]` |

`./scripts/ios-e2e.sh` starts the harness compose stack (backend on `127.0.0.1:8080`, harness on `8090`, device proxy on
`8091`), runs the sample app's UI tests (6) and hosted Keychain / App Group tests (2) on the Simulator and writes
`harness/build/ios-e2e/` (`summary.json`, result bundles, `logs/`); it ends with `iOS E2E: 8/8 passed, 1 skipped`. The
Simulator does not run a Notification Service Extension for `xcrun simctl push` or `.apns` files, so the tests open the
sample's debug URL `rpsample://debug-push?...`, which runs the same `ReliablePushExtension` code in the app process and
shows the result as a local notification that the test taps. "Offline" is the harness device proxy's online/offline switch
(the Simulator shares the Mac's network and has no airplane mode). `.github/workflows/ios-ci.yml` never runs it: CI builds
and unit-tests the package, builds the sample (Debug and Release) and checks the size and the manifest.

## Real FCM pushes (optional, maintainer's Firebase project)

`./scripts/android-e2e.sh` (emulator, needs the Android SDK) normally ends with `Android E2E: 8/8 passed, 1 skipped`: the
ninth entry, `fcm-real-delivery`, sends a push through the real FCM HTTP v1 API and checks that Google Play services wakes
the killed sample app, which reports `DELIVERED`. It runs only when both files below exist; without them it is `SKIPPED`
(counted as neither passed nor failed, exit code 0) and every build and test passes as usual.

1. In the Firebase console create a project and add an Android app with package `io.reliablepush.sample`.
2. Download `google-services.json` into `android/sample/` (the Google Services Gradle plugin is applied only when it exists).
3. Create a service-account key that may send FCM messages (role "Firebase Cloud Messaging API Admin") and save it as
   `harness/build/fcm/service-account.json`; `chmod 600` it. Compose mounts `harness/build/fcm/` read-only at `/run/fcm`,
   and the harness container runs as the invoking user so it can read the key.
4. Run `./scripts/android-e2e.sh --test fcm-real-delivery`. The emulator needs Google Play services (the default AVD has
   them) and internet access.

Both files are git-ignored and never committed, logged or printed. The harness reports `fcmEnabled` in
`GET /console/api/apps`, the console API shows only `hasFcmToken` for a device, and a push to a user with real devices that
reported an FCM token adds an `fcm_push` entry (`SENT`, `UNREGISTERED` or `FAILED` with the HTTP status) to the
notification's timeline; an `UNREGISTERED` answer drops the device's token. When you are done, delete the key in the Google
Cloud console.

## Real APNs pushes (optional, maintainer's Apple Developer team)

`./scripts/ios-e2e.sh` normally ends with `iOS E2E: 8/8 passed, 1 skipped`: the ninth entry, `apns-real-delivery`, sends a
push through the real APNs sandbox and checks that the OS starts the Notification Service Extension of the terminated
sample app, which reports `DELIVERED` (the extension reads the device token from the shared Keychain group and App Group).
On Apple-silicon Macs the Simulator receives real APNs sandbox pushes. It needs an app signed by a paid Apple Developer
Program team with Push Notifications, so it runs only when all three pieces below exist; without any of them it is `SKIPPED`
(neither passed nor failed, exit code 0, the hint names the missing piece) and every build and test works as usual, with
ad hoc signing and no team. This is a maintainer step; scripts and agents never create keys, sign in or enter credentials.

1. In the Apple Developer portal register the bundle id `io.reliablepush.sample` (or your own, then set `RP_BUNDLE_ID` when
   running the script and `RP_APP_GROUP` to match) and the App Group `group.io.reliablepush.sample` for it and for the
   `.NSE` extension, with the Push Notifications capability on the app.
2. Create an APNs authentication key (Keys, "Apple Push Notifications service") and save it as
   `harness/build/apns/AuthKey.p8`; `chmod 600` it. Compose mounts `harness/build/apns/` read-only at `/run/apns` (the
   scripts create it empty), and the harness container runs as the invoking user so it can read the key.
3. Write `harness/build/apns/apns.properties`:

   ```
   keyId=ABC123DEFG
   teamId=TEAM123456
   topic=io.reliablepush.sample
   environment=sandbox
   ```

4. `export IOS_DEVELOPMENT_TEAM=<your 10-character team id>`.
5. Run `./scripts/ios-e2e.sh --test apns-real-delivery`. Phase `apns` builds the sample with `RP_APNS=1` (Debug.xcconfig then
   uses `ReliablePushSample-APNs.entitlements` with `aps-environment` and signs with your team; the app registers for remote
   notifications and reports its APNs token to the harness), registers, waits for the token, terminates the app and sends a
   console push; `DELIVERED` must follow within 60 s. Without `RP_APNS` the sample never asks for an APNs token.
6. When you are done, revoke the key in the Apple Developer portal.

Everything under `harness/build/apns/` is git-ignored and never committed, logged or printed. The harness reports
`apnsEnabled` in `GET /console/api/apps`; the console API shows only `hasApnsToken` for a device. A push to a user with
real devices that reported an APNs token adds an `apns_push` entry (`SENT`, `UNREGISTERED` or `FAILED` with the HTTP status
and Apple's reason code) to the notification's timeline; `UNREGISTERED` (HTTP 410, or 400 `BadDeviceToken`) drops the device's
token. The provider JWT (ES256, cached 50 minutes) and the token never reach a log line. Unit tests use a test EC key
generated in the test and a local stub; they never contact Apple.

## Web console and demo

`./scripts/demo.sh [--realtime]` (Windows: `scripts\demo.ps1`) starts the same stack as the compose project
`reliable-push-demo` and opens the console at <http://127.0.0.1:8090/>. The default is the fast clock; `--realtime` uses real
minutes. It keeps running; the script prints the stop command:
`docker compose -p reliable-push-demo -f harness/compose/docker-compose.yml -f harness/compose/demo.override.yml down -v`.

The console has six screens: Devices, Send push, Live tracking, Fallback inbox, Scenarios and Logs. A permanent banner reads
"Test harness — simulated data, not for production". No secret is shown in the page or exposed by its API; live updates use
Server-Sent Events and reconnect automatically. The console API accepts writes (`POST`) only with `Content-Type: application/json`
(an explicit `{}` when there is nothing to send) and rejects cross-site requests (`Origin` or `Sec-Fetch-Site` from another
origin) with 403. Every `/console/api/` request (reads too) must also carry a `Host` of `localhost`, `127.0.0.1`, `[::1]`,
`10.0.2.2` or `harness` (any port), else 403, which defeats DNS rebinding. `curl`, the scripts and the emulator's `HttpURLConnection` send neither cross-site header. The console and the harness are never built into the backend
image or deployed with a Helm chart.

For real apps (the Android sample on an emulator, which reaches the host harness at `10.0.2.2:8090`) the console API also has
`POST /console/api/grants {appId, userId}` (201 `{grant, expiresAt}`: a signed grant, minted by the same component as the
simulator's; the only endpoint that returns one, never logged) and `POST /console/api/real-devices {appId, userId, deviceId,
platform, label}` (201; kept in memory, at most 50). Real devices show in the Devices list as `REAL` (the harness holds no
token for them, so no refresh/offline buttons); `GET /console/api/devices` marks every entry `"kind": "REAL"` or
`"SIMULATED"`. The push preview also shows the `adb shell am broadcast` line for the sample app's debug receiver; its values
are single-quoted twice (host shell and device shell), so paste it into a POSIX host shell (not PowerShell or cmd). For the iOS
sample app the preview also shows `xcrun simctl openurl booted 'rpsample://debug-push?...'` (percent-encoded, single-quoted).

The iOS Simulator shares the Mac's network and has no airplane mode, so the iOS sample reaches the backend through a TCP proxy
in the harness container, published on `127.0.0.1:8091` only (to `api:8080`; `HARNESS_DEVICEPROXYTARGET`, and port 8091 must be
free on the host). `GET /console/api/device-network` returns `{online, enabled}` and `POST /console/api/device-network
{"online": false}` switches it: offline closes every open connection and closes new ones at once, so clients see a transport
error, not an HTTP status. The Devices screen has the same switch. Without the target (plain `mvn` runs) nothing listens.
The proxy listens on `127.0.0.1` unless `HARNESS_DEVICEPROXYBIND` (`harness.device-proxy-bind`) says otherwise. Compose sets
`0.0.0.0` inside the container, because Docker forwards the published port to the container's interface; on the host it
is still published on `127.0.0.1` only. Do not set `0.0.0.0` on a harness started outside a container: that would expose
the backend to your network.

## Load tests (local only)

`./scripts/load.sh [--simulation ingest|burst|all] [--api-pool N] [--worker-concurrency N] [--worker-pool N] [--workers 1|2] [--keep]`
(defaults: `all`, 10, 100, 10, 1). Never run in CI. Needs Docker with the compose plugin and ports 8080, 8090 and 8091 free on
127.0.0.1, so it cannot run next to the E2E or demo stack. `--simulation all` takes about 15 minutes.

- **Stack:** the E2E compose file plus `loadtest/compose/load.override.yml` (project `reliable-push-load`, real clock, API
  limited to 2 CPUs / 1 GiB, workers on the management port 8081) and a Gatling container on the same compose network,
  so host port forwarding never caps the throughput. Its Maven cache is the external volume `reliable-push-gatling-m2`.
- **Simulations:** `ingest` is the NFR-2 criterion: 500 `POST /v1/notifications`/s and 1,000 `POST /v1/events`/s (one
  event each) for 5 minutes after a 60 s ramp, asserting ≤ 0.1 % failed requests, p95 ≤ 100 ms and p99 ≤ 250 ms per
  request group. `burst` registers `LOAD_BURST_SIZE` notifications (default 30,000 at 1,500/s) that fall due together;
  `BurstDrainProbe` samples the workers' `/actuator/prometheus` until all are triggered; a warm-up before the burst
  (`LOAD_BURST_WARMUP`, default 30 s, request name `notify-warm-up`) keeps the cold JIT out of the asserted `notify`
  requests, so `burst` alone works on a fresh stack. `all` runs ingest first.
- **Results:** `harness/build/load/summary.json` (per simulation: `status` `PASSED`/`FAILED`/`INVALID`, metrics, the
  effective config with `nfr2Criterion`, generator CPU), Gatling HTML reports in `harness/build/load/gatling/`, the probe
  output in `burst.json`, container logs in `logs/`. Exit code 0 only if every simulation `PASSED`. A run whose Gatling
  container averages more than 90 % CPU is `INVALID` (the generator, not the backend, was the limit). Only an `ingest`
  entry with `status` `PASSED` and `config.nfr2Criterion` `true` (the run used the NFR-2 rates, 60 s ramp, ≥ 300 s
  steady, API at 2 CPUs / 1g) validates NFR-2.
- **Settings:** environment variables `LOAD_DEVICES`, `LOAD_NOTIFICATIONS_PER_SECOND`, `LOAD_RAMP`, `LOAD_STEADY`,
  `LOAD_BURST_SIZE` (a multiple of `LOAD_BURST_PER_SECOND`), `LOAD_BURST_PER_SECOND`, `LOAD_BURST_WARMUP`,
  `LOAD_READY_TIMEOUT`, `LOAD_WORKER_CPUS`, `LOAD_WORKER_MEMORY`. Details of the module: [`loadtest/README.md`](../loadtest/README.md).
- **Measured results** and how to reproduce each table row: [operations.md, "Measured capacity"](operations.md#measured-capacity).

## Helm chart checks

- **`deploy/helm/scripts/validate.sh`** (Docker only; CI runs it in `.github/workflows/helm-ci.yml`): `helm lint --strict`
  in both modes, the helm-unittest suites in `deploy/helm/reliable-push/tests/`, and `helm template | kubeconform -strict`
  for four renders (default, split, GKE overlay, OpenShift overlay) against the Kubernetes 1.30 schemas, the vendored Route
  schema (`deploy/helm/schemas/`) and the CRD catalog at a pinned commit (ServiceMonitor, PodMonitoring, BackendConfig;
  fetched over the network). Tool images are pinned by digest in `deploy/helm/tools.env`; nothing is installed on the
  host.
- **`./scripts/helm-smoke.sh [--keep]`** (local only, never in CI; needs kind, kubectl, curl, JDK 21): creates the kind
  cluster `rp-smoke` (node image from `tools.env`), builds and loads the backend image, installs PostgreSQL, a slow dummy
  fallback receiver and the chart in split mode (1 API, 2 workers, UID 1000870000 / GID 0 like OpenShift), then checks the
  rollouts, `helm test`, the API through its Service, `/actuator` answering 404 on the API port, and a worker
  `kubectl rollout restart` while webhook calls are in flight (all 200 rows triggered exactly once, no ERROR log, no
  `fallback.reclaimed`). Each check prints `PASS`/`FAIL`; exit 0 = passed. It uses its own kubeconfig in
  `harness/build/helm-smoke/` and never touches `~/.kube/config`; helm runs from `HELM_IMAGE`. Without `--keep` the
  cluster is deleted at the end; with `--keep` delete it with
  `kind delete cluster --name rp-smoke --kubeconfig harness/build/helm-smoke/kubeconfig`. A kept cluster is marked with
  its control-plane container ID: the next run reuses (and may delete) only that cluster, and refuses an `rp-smoke`
  cluster it did not create. Each run starts with empty `logs/`.
