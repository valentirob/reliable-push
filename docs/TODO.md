# TODO — remaining work after 0.1.0

Everything the three planned cycles (backend + harness, Android SDK, iOS SDK) set out to build is done and released as
`v0.1.0` (2026-11-06). This page lists what is left. For each item it says why the item is deferred and what unblocks it:
an **action** (someone does something) or a **date**.

Agents: remind the maintainer of the open items in section 1 at the start of a session. When an item is done, delete it
here and record the result in [STATUS.md](STATUS.md).

| # | Item | Owner | Unblocked by |
|---|---|---|---|
| 1.1 | Real APNs delivery and the locked-device check | Maintainer | Action: enrol in the Apple Developer Program |
| 1.2 | Real FCM delivery | Maintainer | Action: create a Firebase project |
| 1.3 | Publish the Android SDK and the Java client to Maven Central | Maintainer | Action: verify a Central namespace and create a signing key |
| 2.1 | Android mode v2 (`AndroidConfigV2`) | Agent | Date: Google ships `onNotificationReceived` as GA (check quarterly, next 2027-02-01) |
| 3.1 | Install the Helm chart on a real GKE and OpenShift cluster | Maintainer + agent | Action: access to a cluster |
| 3.2 | Run the PowerShell scripts on Windows | Maintainer + agent | Action: a Windows machine with PowerShell 7 and Docker |
| 4.1 | iOS minor M7: a burst can leave one `DELIVERED` queued | Agent | Action: the next iOS SDK change (0.2.0 scope) |
| 4.2 | Harness minor M12: the device proxy binds all interfaces | Agent | Action: the next harness change (0.2.0 scope) |
| 4.3 | Decide whether the `api` profile gates the HTTP endpoints | Maintainer | Action: a deployment that needs worker-only instances without an HTTP API |
| 4.4 | Cold-JIT p99 on a burst-only load run | Agent | Action: only if load tests ever run in CI |
| 5.1 | Pick the 0.2.0 scope | Maintainer | Action: maintainer decision (nothing is planned after 0.1.0) |

## 1. Maintainer-only: needs personal accounts and credentials

Agents never create accounts or keys, sign in, or enter credentials. These steps wait for the maintainer.

### 1.1 Real APNs delivery and the locked-device check (iOS)

- **What:** run `./scripts/ios-e2e.sh --test apns-real-delivery`. It passes when `DELIVERED` arrives within 60 s. Then
  send one push while a real device is locked, with the app built with Data Protection Complete.
- **Why deferred:** a push through the real APNs sandbox needs an app signed by a **paid Apple Developer Program**
  team, with the Push Notifications capability. The maintainer has only a free Apple ID (2026-11-06). Without it the
  scenario is `SKIPPED` (`iOS E2E: 8/8 passed, 1 skipped`).
- **What it proves:** the Notification Service Extension of a terminated app is started by the OS and reports
  `DELIVERED`. The locked-device push is the only way to check two findings of the cycle 3 review, which the Simulator
  cannot verify:
  - **I1:** the file protection class `completeUntilFirstUserAuthentication` on the queue and the lock files;
  - **M5:** the local-network exception of the extension, which is Debug-only.
- **Unblocked by:** enrolling in the Apple Developer Program. Then follow the steps in
  [testing.md, "Real APNs pushes"](testing.md#real-apns-pushes-optional-maintainers-apple-developer-team): bundle id and
  App Group, `harness/build/apns/AuthKey.p8`, `harness/build/apns/apns.properties`, `IOS_DEVELOPMENT_TEAM`.

### 1.2 Real FCM delivery (Android)

- **What:** run `./scripts/android-e2e.sh --test fcm-real-delivery`. It passes when Google Play services wakes the
  killed sample app and the app reports `DELIVERED`.
- **Why deferred:** it needs a Firebase project and a service-account key, which belong to the maintainer. Without them
  the scenario is `SKIPPED` (`Android E2E: 8/8 passed, 1 skipped`).
- **Unblocked by:** creating a Firebase project with the Android app `io.reliablepush.sample`. Then follow the steps in
  [testing.md, "Real FCM pushes"](testing.md#real-fcm-pushes-optional-maintainers-firebase-project):
  `android/sample/google-services.json` and `harness/build/fcm/service-account.json` (`chmod 600`).

### 1.3 Publish the Android SDK and the Java client to Maven Central

- **What:** release `io.reliablepush:reliable-push-android` and `io.reliablepush:reliable-push-client` to Maven Central.
  Today they install only into the local repository (`publishToMavenLocal` / `mvn install`). The iOS SDK needs no
  registry: SwiftPM resolves it from the `v0.1.0` tag.
- **Why deferred:** Central needs a **verified namespace** and a **signing key**:
  - The namespace `io.reliablepush` requires owning the `reliablepush.io` domain. The alternative is
    `io.github.valentirob`, which changes the coordinates.
  - The signing key is a GPG key whose secret stays with the maintainer. The Android build already signs when
    `SIGNING_KEY` and `SIGNING_PASSWORD` are set.
- **Unblocked by:** the maintainer choosing the namespace, verifying it on central.sonatype.com and creating the key. An
  agent can then add the publishing setup and a release workflow that reads the secrets from GitHub Actions.

## 2. Waiting for an external event

### 2.1 Android mode v2 (`AndroidConfigV2`)

- **What:** the opt-in FCM mode described in the spec (§7): the backend sends a real notification with
  `mutable_content: true`, and the SDK confirms it from `onNotificationReceived` within 20 s, as on iOS.
- **Why deferred:** it needs `firebase-messaging` ≥ 26.1.0, which exists only as an early-access build (`26.1.0-eap01`).
  The project uses no pre-release dependencies.
- **Unblocked by:** Google publishing `onNotificationReceived` as GA. **Check quarterly; next check 2027-02-01.** Until
  then the data-only mode v1 covers delivery tracking, including when the app is killed.

## 3. Waiting for an environment

### 3.1 Install the Helm chart on a real GKE and OpenShift cluster

- **What:** install the chart in combined and split mode on GKE and on OpenShift. On GKE also check the `BackendConfig`
  health check and its firewall note.
- **Why deferred:** no cluster is available. Today the chart is checked by rendering and schema validation in CI
  (`helm lint`, helm-unittest, kubeconform) and by a real install on local kind (`./scripts/helm-smoke.sh`), with an
  OpenShift-like random UID.
- **Unblocked by:** access to a GKE project and an OpenShift cluster. Follow [deployment.md](deployment.md).

### 3.2 Run the PowerShell scripts on Windows

- **What:** run `pwsh scripts/e2e.ps1` and `scripts\demo.ps1` on Windows. Also run the PowerShell commands shown in the
  docs.
- **Why deferred:** they were written for parity with the shell scripts but never run: there is no Windows machine.
- **Unblocked by:** a Windows machine with PowerShell 7 and Docker Desktop.

## 4. Technical backlog (low impact, deliberately parked)

### 4.1 iOS minor M7: a burst can leave one `DELIVERED` queued

- **What:** when many pushes arrive together, one `DELIVERED` event can stay in the queue until the next flush (the next
  push, app launch or open).
- **Why deferred:** it is not lost, only late. The fallback timeouts are minutes, so the impact is small. It was parked
  in the cycle 3 fix wave (ruling R21).
- **Unblocked by:** the next change to the iOS SDK (0.2.0 scope).

### 4.2 Harness minor M12: the device proxy binds all interfaces

- **What:** inside the harness container the device proxy listens on every interface. Compose publishes it on
  `127.0.0.1:8091` only, so it is not reachable from other machines.
- **Why deferred:** the harness is a local test tool that is never deployed. It was parked in the cycle 3 fix wave.
- **Unblocked by:** the next change to the harness (0.2.0 scope).

### 4.3 Decide whether the `api` profile gates the HTTP endpoints

- **What:** today only `worker` enables beans. Every instance serves the HTTP API, and `api` only marks an API-only
  instance ([architecture.md](architecture.md#roles-and-profiles)).
- **Why deferred:** it is a design choice, not a defect. The Helm chart's split mode simply does not route traffic to
  the workers.
- **Unblocked by:** a deployment that needs worker-only instances without any HTTP API. Then gate the controllers and
  filters behind `api` and update the chart.

### 4.4 Cold-JIT p99 on a burst-only load run

- **What:** `./scripts/load.sh --simulation burst` on a freshly started stack can fail its p99 ≤ 250 ms assertion
  (400-460 ms) because the JIT is cold.
- **Why deferred:** the built-in warm-up (`LOAD_BURST_WARMUP`) and `--simulation all` avoid it, and load tests are
  local only.
- **Unblocked by:** only if load tests ever run in CI.

## 5. Next scope

### 5.1 Pick the 0.2.0 scope

- **What:** nothing is planned after 0.1.0. Candidates: items 1.3, 2.1, 4.1 and 4.2, or new requirements from
  [requirements.md](requirements.md) "Out of scope (for now)", for example web push.
- **Why deferred:** it is a product decision.
- **Unblocked by:** the maintainer choosing the scope. Then write a spec or plan in `docs/plans/` as for the earlier
  cycles.
