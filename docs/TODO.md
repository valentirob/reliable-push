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
| 4.3 | Decide whether the `api` profile gates the HTTP endpoints | Maintainer | Action: a deployment that needs worker-only instances without an HTTP API |
| 4.4 | Cold-JIT p99 on a burst-only load run | Agent | Action: only if load tests ever run in CI |
| 4.5 | Oldest live trend day undercounts with `retention-days` of 7 or less | Agent | Action: a spec line for the stored-or-live rule, or an install with `retention-days <= 7` |
| 4.6 | No backfill of pre-upgrade history into `daily_stats` | Agent | Action: a maintainer or user asking for trends before the 0.2.0 upgrade |
| 4.7 | Live-query cost of the 7-day overview and the trends on large tables | Agent | Action: a load run or install with tens of millions of notifications showing slow admin queries |
| 5.1 | 0.2.0 release | Maintainer | Action: maintainer decision to release (the ops dashboard is built on `feature/ops-dashboard`) |

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

### 4.5 Oldest live trend day undercounts with `retention-days` of 7 or less (ops dashboard)

- **What:** the trends compute the days from `today - W` (`W = min(7, retention-days)`) through today live from
  `notification`. With `retention-days` of 7 or less, retention has already purged part of day `today - W`, so its live number is too
  low, while the stored `daily_stats` row from the night before retention ran is complete ([admin.md](admin.md)
  "Limits"). Suggested rule: for day `today - W` use the stored row when its `computed_at` is on or after today 00:00 UTC
  (tonight's rollup, computed before tonight's retention), else compute it live.
- **Why deferred:** it affects only installs with `retention-days <= 7` (the default is 30) and only the oldest day of
  the window; the rule changes the spec's trends definition (spec §4), which needs a spec line first.
- **Unblocked by:** a spec line for the rule in `docs/specs/2026-11-06-ops-dashboard-design.md` §4 (then a test in
  `DailyRollupIT.trendsRoute` and the change in the trends route), or an install that runs with `retention-days <= 7`.

### 4.6 No backfill of pre-upgrade history into `daily_stats` (ops dashboard)

- **What:** `daily_stats` starts with the first nightly rollup after the 0.2.0 upgrade, which covers only the last
  `min(7, retention-days)` days. Days older than that, still present in `notification` at upgrade time, never get a
  stored row, so long trends start empty a week before the upgrade.
- **Why deferred:** the upgrade gap shrinks to nothing within `retention-days`, and nobody has asked for the older
  trends; a backfill (a one-off rollup of every day still in `notification`) is a long-running job to design (lock,
  chunking, duration on large tables).
- **Unblocked by:** a maintainer or user asking for trends that reach before the upgrade. Then add a one-off command or
  an `admin.rollup.backfill-days` setting, with a spec line.

### 4.7 Live-query cost of the 7-day overview and the trends on large tables (ops dashboard)

- **What:** the overview's `7d` window and the live days of the trends aggregate `notification` on every request
  (through `notification_app_created`), so their cost grows with the traffic of the last 7 days. The page refreshes the
  overview every 30 s.
- **Why deferred:** the admin API is low-traffic (a few operators, rate-limited per key) and the load tests (NFR-2)
  did not include admin queries; no measurement shows a problem yet.
- **Unblocked by:** a load run or an install with tens of millions of notifications in 7 days showing slow admin
  queries. Then serve the closed days of the 7-day window from `daily_stats` (only today live) and measure again.

## 5. Next scope

### 5.1 Release 0.2.0 (the ops dashboard)

- **What:** 0.2.0 is the read-only ops dashboard ([admin.md](admin.md), FR-16 and FR-17), in progress on
  `feature/ops-dashboard` (cycle 4). Anything beyond it needs a new scope: items 1.3 and 2.1, or new requirements from
  [requirements.md](requirements.md) "Out of scope (for now)", for example web push.
- **Why deferred:** releasing is a maintainer decision; the 0.2.0 release comes after the merge to `develop`.
- **Unblocked by:** the maintainer deciding to release.
