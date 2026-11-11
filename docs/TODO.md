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
| 1.3 | Publish the Android SDK, the Java client and the payload jar to Maven Central | Maintainer | Action: verify a Central namespace and create a signing key |
| 2.1 | Android mode v2 (`AndroidConfigV2`) | Agent | Date: Google ships `onNotificationReceived` as GA (check quarterly, next 2027-02-01) |
| 3.1 | Install the Helm chart on a real GKE and OpenShift cluster | Maintainer + agent | Action: access to a cluster |
| 3.2 | Run the PowerShell scripts on Windows | Maintainer + agent | Action: a Windows machine with PowerShell 7 and Docker |
| 4.3 | Decide whether the `api` profile gates the HTTP endpoints | Maintainer | Action: a deployment that needs worker-only instances without an HTTP API |
| 4.4 | Cold-JIT p99 on a burst-only load run | Agent | Action: only if load tests ever run in CI |
| 4.5 | Oldest live trend day undercounts with `retention-days` of 7 or less | Agent | Action: a spec line for the stored-or-live rule, or an install with `retention-days <= 7` |
| 4.6 | No backfill of pre-upgrade history into `daily_stats` | Agent | Action: a maintainer or user asking for trends before the 0.2.0 upgrade |
| 4.7 | Live-query cost of the 7-day overview and the trends on large tables | Agent | Action: a load run or install with tens of millions of notifications showing slow admin queries |
| 4.8 | Due-set indexes of the circuit detector have no `app_id` | Agent | Action: an install with many apps (tens or more) or a measurement showing slow circuit checks |
| 4.9 | Circuit breaker untested under load | Agent | Action: a load run with the breaker on (a mixed burst: one app delivered, one not) |
| 4.10 | Circuit event times can be up to the lock wait out of order | Agent | Action: clamp the check instant to the circuit's `since` |
| 4.11 | iOS size gate: about 240 bytes left in the last 16 KB code page | Maintainer + agent | Action: maintainer decision on the metric (proposal: measure what ships in the app and the extension) |
| 4.12 | Harness device simulator drops `429` / `503` / network-error events (the SDKs keep them) | Agent | Action: a scenario that needs the SDKs' retry behaviour in the simulator |
| 5.1 | Pick the 0.3.0 scope | Maintainer | Action: maintainer decision (0.2.0 shipped on 2026-11-08) |
| 6.1 | Yearly volume of tracked notifications and share that falls back, for the target installation | Maintainer | Action: confirm the figures with the owners of the existing push backend |
| 6.2 | Peak load: the largest campaign and how long its send takes | Maintainer | Action: ask the owners of the existing push backend |
| 6.3 | Status API of the existing push backend: endpoint, key (its message id), batch support, rate it accepts | Maintainer | Action: get the API contract |
| 6.4 | Infrastructure available where reliable-push will run: managed PostgreSQL, Redis, a message bus | Maintainer | Action: ask the platform team |
| 6.5 | How much history reliable-push must keep when the existing backend is the system of record | Maintainer | Action: maintainer decision |
| 6.6 | Rollout next to the delivery-confirmation provider in use today: how the sender chooses per device, and whether both SDKs can live in one app | Maintainer + agent | Action: the rollout rules, then a test on real devices (needs 1.1 and 1.2) |
| 6.7 | Who decides the fallback after an unconfirmed push: reliable-push (as today) or the existing backend | Maintainer | Action: find out what the existing backend does today when a push is not confirmed |

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

### 1.3 Publish the Android SDK, the Java client and the payload jar to Maven Central

- **What:** release `io.reliablepush:reliable-push-android`, `io.reliablepush:reliable-push-client` and
  `io.reliablepush:reliable-push-payload` to Maven Central.
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
- **Why deferred:** it needs `firebase-messaging` ≥ 26.1.0, which exists only as an early-access build (`26.1.0-eap01`)
  outside the public Maven repositories, with no announced GA date. The project uses no pre-release dependencies.
- **Where the switch happens:** in the payload jar (`FcmPayloads`), so senders that build their FCM messages with it
  change nothing; the Android SDK gains the `onNotificationReceived` path. Until then tracked Android pushes are
  data-only messages and the SDK posts the notification (the market-standard model, [android.md](android.md)).
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

### 4.8 Due-set indexes of the circuit detector have no `app_id` (fallback circuit breaker)

- **What:** every tick the detector counts each app's due set through the partial indexes `notification_due` (`due_at`)
  and `notification_closed` (`closed_at`), filtering `app_id` row by row. Each app's read therefore scans the window's
  rows of all apps (bounded by `min-due` only for the matching rows), so the cost per tick grows with apps x rows in the
  window ([operations.md](operations.md#scaling-and-capacity)).
- **Why deferred:** fine for a few apps; an extra index (`(app_id, due_at)` / `(app_id, closed_at)`, partial) costs
  writes on the hottest table, and no measurement shows a problem yet.
- **Unblocked by:** an install with many apps or a measurement of slow circuit checks (`fallback.circuit_check_failed`
  with a lock timeout, or a long tick). Then add the two partial indexes online (`create index concurrently`) and measure.

### 4.9 Circuit breaker untested under load (fallback circuit breaker)

- **What:** the load test runs with `fallback.circuit-breaker.enabled: false` (its burst is never delivered, exactly the
  pattern the breaker stops), so the detection and suppression cost at burst scale is not measured.
- **Why deferred:** the integration tests and the E2E scenario cover the behaviour; cost matters only at scale.
- **Unblocked by:** a load run with the breaker on, for example a burst split across a delivered and an undelivered app,
  measuring the drain rate of the delivered app and the suppression rate of the other.

### 4.10 Circuit event times can be up to the lock wait out of order (fallback circuit breaker)

- **What:** a tick takes its check instant before waiting for the detector lock (at most 2 s). If another worker or a
  resume commits a transition during that wait, the next event can carry an `at` (and a new `since`) up to 2 s earlier
  than the one before it, and `min-open` can effectively shorten by the same amount. The history is ordered by `id`,
  so the console shows the right order; detection is unaffected.
- **Why deferred:** cosmetic for the stored times; found by the final review of cycle 5.
- **Unblocked by:** nothing; clamp the instant to `max(at, current.since)` when writing a transition.

### 4.11 iOS size gate headroom (NFR-10)

- **What:** `ios/scripts/size.sh` compiles the whole SDK (app side and extension side) into one stripped arm64 dylib:
  292,896 bytes against the gate of 307,200 (2026-11-08, `v0.2.0`). The file grows in 16 KB pages: the first page of
  `__TEXT` is almost only the Mach-O header, about 196 KB is code and metadata, and the last code page has about
  240 bytes free, so the next addition adds a page (about 309 KB) and fails the gate. The extension itself does little
  (read `rp_tid`, queue, settings check, send within 2 s, hand the content back once); the size comes from the app-side
  code (registration, `ensureRegistered`, device API) and from Swift's code generation (`async`/`await`, actors,
  `Codable`).
- **Why deferred:** the gate is a proxy. SwiftPM links the library statically by default, and the linker drops unused
  code, so the extension and the app probably carry less than this dylib; that has not been measured.
- **Unblocked by:** the maintainer choosing between: measure what ships (link the SDK into the sample app and extension,
  gate the size each one adds, still under 300 KB; recommended), raise the dylib gate, or trim code (for example replace
  `Codable` with hand-written JSON; smaller but more fragile).

### 4.12 Harness device simulator drops `429` / `503` / network-error events

- **What:** the simulator keeps an event queued only after a `401` (`renewalNeeded`); after `429`, `503` or a network
  error it reports the outcome and drops the event, while the SDKs keep it and retry (`Retry-After`, the next launch,
  the Android retry job).
- **Why deferred:** no E2E scenario depends on it (`rate-limited` checks the `429` itself); the device suites cover the
  SDKs' own retries.
- **Unblocked by:** a scenario that needs the SDK retry behaviour in the simulator. Then keep the event and retry it at
  the next flush, honouring `Retry-After`.

## 5. Next scope

### 5.1 Pick the 0.3.0 scope

- **What:** 0.2.0 shipped on 2026-11-08: the ops dashboard ([admin.md](admin.md), FR-16 and FR-17), the fallback
  circuit breaker ([operations.md](operations.md#fallback-circuit-breaker), FR-18 and FR-19) and the sidecar integration
  revision ([integration-guide.md](integration-guide.md), FR-20 to FR-24). Nothing is planned after it. Candidates: a
  push gateway mode (if the sender teams cannot make the sidecar changes), items 1.3 and 2.1, or new requirements from
  [requirements.md](requirements.md) "Out of scope (for now)", for example web push.
- **Why deferred:** it is a product decision.
- **Unblocked by:** the maintainer choosing the scope. Then write a spec or plan in `docs/plans/` as for the earlier
  cycles.

## 6. Open checks of the scale and integration review (2026-11-11)

The maintainer asked for a review of the whole design against a target installation: millions of users, hundreds of
millions of tracked notifications a year, an existing push backend that stays the system of record for notification
status, and a delivery-confirmation provider already in use that reliable-push would replace step by step. The
installation's own figures are deliberately **not** in this public repository; the maintainer has them, with the
review report. The design work waits for the answers below; until then the review uses stated assumptions.

### 6.1 Yearly volume and fallback share

- **What:** the number of notifications a year that need tracking, and the share that ends in a fallback.
- **Why it matters:** it sizes the storage and the outbound status calls. The average rate is low; the peak (6.2)
  decides the sizing.
- **Unblocked by:** the owners of the existing push backend confirming the figures.

### 6.2 Peak load

- **What:** the largest campaign (pushes) and the time its send takes; the busiest hour of a normal day.
- **Why it matters:** the existing backend sends single pushes, so a campaign is one registration per push, and the
  events come back within seconds. One API replica was measured at 500 registrations/s plus 1,000 events/s
  ([operations.md](operations.md#measured-capacity)); the peak says how many replicas and how much database.
- **Unblocked by:** the owners of the existing push backend.

### 6.3 Status API of the existing push backend

- **What:** the existing backend keeps the notification status. reliable-push must call it to change the status of a
  notification, keyed by the backend's own message id. Needed: the endpoint and its authentication, the key (is the
  message id a UUID, can it be the `trackingId` or does it travel as `externalId`), whether one call can carry several
  status changes, the rate it accepts, what it answers on a repeat.
- **Why it matters:** this is the heaviest flow, up to two calls per tracked notification (delivered, read), several
  times the fallback calls. Without a batch call it also sets the load on the existing backend.
- **Unblocked by:** the API contract. Then a spec for the outbound status calls (today reliable-push only has the
  fallback webhook).

### 6.4 Infrastructure available

- **What:** what the target environment already runs and operates: a managed PostgreSQL, Redis or Valkey, Kafka or
  Pub/Sub.
- **Why it matters:** the storage choice. With the existing backend as the system of record, reliable-push keeps only
  the device registry and the notifications still waiting; PostgreSQL holds that easily, and another store is worth it
  only when it is already operated there.
- **Unblocked by:** the platform team.

### 6.5 History kept in reliable-push

- **What:** whether reliable-push still needs its own history (30 days of notifications and events, 400 days of daily
  trends, the dashboard's search) once the status lives in the existing backend.
- **Why it matters:** history is what makes the database heavy, not the live data. Keeping hours instead of days
  removes most of the storage, the nightly deletes and several indexes.
- **Unblocked by:** a maintainer decision.

### 6.6 Rollout next to the provider in use today

- **What:** during the rollout the existing backend chooses, per device and its registration state, between the
  provider in use today and a direct push tracked by reliable-push. Open points: how the backend learns that a device
  has the reliable-push SDK (per user from the registration answer, or per device from a flag the app sends with its
  push token); what an Android app **without** the SDK does with a tracked data-only message (it may show nothing);
  whether the two SDKs can share one `FirebaseMessagingService` and one iOS Notification Service Extension.
- **Why it matters:** a tracked Android push sent to an app that cannot show it is a lost notification with no
  fallback.
- **Unblocked by:** the rollout rules from the maintainer, then a test on real devices with both SDKs (needs 1.1 and
  1.2).

### 6.7 Who decides the fallback

- **What:** today reliable-push keeps the timer: it knows every registered push and calls the fallback webhook when
  one is not confirmed in time. If the existing backend already reacts to a missing confirmation by itself, reliable-push
  could only forward the events, with no registration call and almost no database.
- **Why it matters:** it is the largest design choice left: it decides whether the register-before-send call, the
  worker, the circuit breaker and the stored fallback text stay.
- **Unblocked by:** finding out what the existing backend does today when a push is not confirmed.
