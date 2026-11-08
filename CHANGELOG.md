# Changelog

All notable changes to this project are documented here. Versions follow [Semantic Versioning](https://semver.org/).

## Unreleased

### Process

- One AI agent context for Claude Code and Antigravity on macOS, Linux and Windows: `.claude/skills` is now generated
  wrapper files instead of a symlink (a symlink breaks on a Windows checkout), `CLAUDE.md` imports the `always_on`
  rules, `AGENTS.md` gains the agent-context table, the maintainer agreement, the environment by operating system and
  the pitfalls seen so far. New skills `plan-feature` (spec, plan, then code) and `release`. `scripts/sync-agent-skills.sh`
  regenerates the wrappers; CI job `agent-context.yml` runs it with `--check`.
- `.gitattributes` keeps `mvnw` and `gradlew` in LF (and the `.cmd` / `.bat` wrappers in CRLF); `.gitignore` ignores
  `.claude/settings.local.json` and `.superpowers/`.
- `scripts/helm-smoke.sh` parses dates with GNU `date` too, so it runs on Linux and WSL.
- Bounded waits (rule `.agents/rules/long-running.md`): `scripts/wait-until.sh` waits for a condition with a mandatory
  timeout and stops as soon as the folder or the process it depends on is gone; `scripts/check-waits.sh` lists the
  running waits and flags overdue ones and shell loops without a timeout. Claude Code (`.claude/settings.json`) and
  Antigravity (`.agents/hooks.json`) run the check at session start and on every prompt or model call; CI runs the
  scripts' tests (`scripts/tests/waits-test.sh`) on Linux.

### Documentation

- The 17 Mermaid diagrams are replaced by 15 SVG diagrams in the style of the diagram-design plugin, with the
  reliable-push palette, generated from TOML sources in `docs/diagrams/src/` by `scripts/diagrams.py` (Python standard
  library), which also enforces the layout rules (4px grid, orthogonal connectors, no arrow behind a box, labels on a
  mask, text inside its box, contrast). The Android SDK diagram is split in two (receiving a push; registration and
  events). CI job `docs-diagrams.yml` checks that the SVGs match their sources.
- The at-a-glance picture is redrawn in the same style and animated: the numbered steps light up in order and a cursor
  shows the fallback timeline (CSS only, so it plays on GitHub and in the presentation; still for reduced motion). The
  PNG is its static frame; the 0.2.0 picture is kept in `docs/images/backup/`.

## 0.2.0 — 2026-11-08

Feature release: the ops dashboard, the per-app fallback circuit breaker and the sidecar integration revision with the
payload jar. It is not compatible with 0.1.x: senders must generate the `trackingId` and register each tracked push
before sending it, and apps must move to the new SDK calls (see "Changed (incompatible, pre-1.0)"). Flyway applies the
migrations `V4` to `V7` at startup.

### Added

- Ops dashboard (0.2.0, FR-16, FR-17): a read-only page at `/admin/` and API at `/v1/admin` served by the backend, enabled
  only when an admin key is configured (otherwise both answer `404` like an unknown path). Views: overview with
  delivery, read and fallback rates and the due backlog; daily trends up to 400 days; search by user, external id,
  tracking id, state and time; notification timeline with events and webhook attempts; a user's devices.
- Admin keys in `reliable-push.admin.keys[]` (stored as SHA-256, scoped to all apps or a list, optional `payload`
  permission; the id `false` is rejected at startup), rate limit `admin.rate-limit-per-minute`, audit events
  `admin.search`, `admin.notification_viewed`, `admin.payload_viewed`, `admin.devices_viewed`.
- Database migrations `V4__admin.sql`: `fallback_attempt` (one row per webhook call) and `daily_stats` (nightly rollup
  by `DailyRollupJob`, kept `admin.rollup-retention-days`, 400 by default; a failed rollup logs WARN `rollup.failed` and
  its stack trace at ERROR); `V5__admin_index.sql`: index `notification_app_created`, built online with
  `create index concurrently` (writes continue during the upgrade; build time grows with the table, see
  `deployment.md`). Flyway's migration lock is now session-level (`spring.flyway.postgresql.transactional-lock=false`).
  `RetentionJob` waits up to 30 minutes for a running rollup, then skips its purge when the rollup is missing
  (`retention.skipped`, `reason` `rollup_missing` or `rollup_running`).
- The dashboard files are served with `Cache-Control: no-cache`; the overview backlog query reads open rows through
  the partial index `notification_due`.
- Helm: `config.admin` in `values.yaml`. With admin keys the Ingress also routes `/admin` and OpenShift gets a second Route for `/admin` (`route.host` is then required); without keys the rendered manifests are unchanged.
- Harness: E2E scenario `admin-dashboard` and `AdminSmokeE2E` (the full run is now 18 entries); a per-run admin key.
- Docs: `docs/admin.md`, admin sections in `api.md`, `security.md`, `deployment.md`, `testing.md`, a new section in
  `overview.md`, the at-a-glance picture and a slide. `deployment.md` also covers checking and dropping an invalid
  `notification_app_created` left by an interrupted build, and states that PgBouncer in transaction pooling mode is not
  supported (Flyway's session-level migration lock); `operations.md` notes the two connections the rollup and the
  retention wait hold in the shared combined-mode pool.
- Fallback circuit breaker (0.2.0, FR-18, FR-19): per app, the worker checks before every claim whether the
  fallbacks coming due in `fallback.circuit-breaker.window` (5 min) are at least `min-due` (50) while less than
  `max-delivered-ratio` (10 %) of their cohort was delivered. Then the circuit opens: the app's due fallbacks end
  `FALLBACK_EXPIRED` without a webhook call and are never re-sent; other apps are not affected. It closes by itself
  after `min-open` (1 min) once `close-min-delivered` (10) newer notifications are delivered. On by default;
  `fallback.circuit-breaker.enabled: false` turns it off, `fallback.suspended: true` stops every fallback of every app.
  Fail closed: a failed check (database error, the detector's advisory lock not obtained in 2 s) claims nothing that
  tick (`fallback.circuit_check_failed`). A tick claims only rows due at the instant its check evaluated (the claim's
  due cut-off), so a burst due within one window fires fewer than `min-due` calls before its circuit opens; a
  suppression pass stops at the next chunk once the circuit is no longer `OPEN`.
- Log events `fallback.circuit_opened`, `fallback.circuit_closed`, `fallback.circuit_forced`, `fallback.suppressed`
  (one per tick and app), `fallback.circuit_check_failed`, `admin.fallback_resumed`; metrics
  `rp_fallback_suppressed_total{app,reason}` (worker role), `rp_fallback_circuit_transitions_total{app,transition}`
  (once per committed transition, both roles) and `rp_fallback_circuit_check_failed_total` (worker role).
- Database migration `V6__fallback_circuit.sql`: `fallback_circuit` (state per app) and `fallback_circuit_event`
  (history with the detector's counters, kept `admin.rollup-retention-days`).
- Admin API and page: `GET /v1/admin/fallback-circuits`, `GET /v1/admin/fallback-circuits/{appId}/events`,
  `POST /v1/admin/fallback-circuits/{appId}:resume` (new admin key permission `fallback-control`; `FORCED` for
  `force-hold`, 60 min; `409` when suspended or disabled, `503` when the detector holds the lock). The overview shows a
  circuit badge per app, a "Fallback circuit" view shows the history and the "Resume fallbacks" button. The dashboard is
  read-only except this resume (FR-16, FR-17 changed).
- Helm: commented `config.fallback` example in `values.yaml`. The load test runs with the breaker disabled.
- Harness: E2E scenario `fallback-circuit` on a dedicated app `circuit` (the full run is now 19 entries); the E2E stack
  tunes the breaker (window 30 s, `min-due` 10, `close-min-delivered` 3, `min-open` 5 s); the demo stack sets the
  production defaults back. The admin page retries a network error only for reads, so a resume is never sent twice.
- Docs: circuit breaker section and runbook "circuit open" in `operations.md`; `admin.md`, `api.md`, `security.md`,
  `architecture.md`, `deployment.md`, `integration-guide.md` (senders see `FALLBACK_EXPIRED` during an open circuit),
  `testing.md`, `overview.md`, `requirements.md`, the at-a-glance picture and the presentation.

- Sidecar integration revision (0.2.0, cycle 6; spec `docs/specs/2026-11-08-sidecar-integration-revision-design.md`,
  FR-20 to FR-24):
  - Payload jar `payload-java/` (`io.reliablepush:reliable-push-payload`, Java 8 bytecode, zero runtime dependencies):
    `TrackingIds.newId()`, `ApnsPayloads.build/decorate`, `FcmPayloads.build/data/decorate`, `PayloadException` with
    nine codes; golden vectors in `payload-java/src/test/resources/vectors/` for other languages. CI runs its tests on
    Java 8 and 21.
  - Sender-generated `trackingId` on `POST /v1/notifications`, idempotent per app (`insert ... on conflict do
    nothing`), stored and returned in lower case; `client-java` `RegisterRequest.trackingId(...)` (required).
  - New final state `NO_DEVICE_REGISTERED`: a registration for a user without an active device is stored closed and
    never falls back (log `notification.no_device_registered`); admin overview, trends, search and daily statistics
    count it as `noDeviceRegistered`; `client-java` knows it.
  - Per-app `device-token-events-grace` (default 90 days, `0` disables, at most 3650): `POST /v1/events` and
    `DELETE /v1/devices/me` (exact route match) accept an expired device token inside it; log field `token_state`,
    metric tag `rp_events_total{token}`.
  - Metrics `rp_notifications_registered_total{outcome}` and the histogram `rp_notification_registration_seconds`.
  - Migration `V7__sidecar_revision.sql`: the new state in `notification_state_check`, `device.token_expires_at`
    (backfilled as `last_seen_at` + 10 years, a conservative upper bound, `not null`), `daily_stats.no_device_registered`. It locks `notification`
    while re-checking the constraint and needs a stop-then-start upgrade (`deployment.md`).
  - SDKs: `ensureRegistered(idTokenProvider)` on iOS (async provider, 30 s cap, completion or `async`) and Android
    (callback provider `(deliver) -> Unit`, 30 s cap); one run at a time; `RegisterResult` gains `notLoggedIn` /
    `NotLoggedIn` and `alreadyRegistered` / `AlreadyRegistered`; a `401` on an event send keeps the events and stores
    `renewalNeeded`.
  - Harness: the fake sender and the console use the payload jar; the device simulator never renews on the event path
    and has an "Ensure registered" button; E2E scenarios `no-device-registered`, `client-tracking-id`,
    `registration-lost`, `token-grace` (the full run is now 23 entries); the load test registers devices and sends
    `trackingId`.
  - Docs: `integration-guide.md` rewritten around the sender flow (checklist, outcome table, residual case, raw APNs,
    Pushy and Firebase Admin SDK examples); `ios.md` and `android.md` (extension step by step, data message and local
    notification, robustness, `ensureRegistered`); `api.md`, `openapi.yaml`, `security.md`, `operations.md`,
    `deployment.md` (V7 upgrade), `architecture.md`, `requirements.md`, `overview.md`, the at-a-glance picture and the
    presentation (new "Sender flow" slide).

### Changed (incompatible, pre-1.0)

- `POST /v1/notifications` requires the sender's `trackingId` (`400` naming the field without it or when it is not a
  UUID, after the `422` field validation). An `externalId` already registered under another `trackingId`, and a
  `trackingId` of another app, are `409` (before: a repeated `externalId` returned the existing record).
- `POST /v1/notifications` for a user without an active device no longer schedules a fallback
  (`NO_DEVICE_REGISTERED`). Clients that switch over states must handle the new value.
- The SDKs no longer renew the device token on their own (not on receive, not in any flush): apps must call
  `ensureRegistered` (or `register`) from time to time, at least at start or foreground and after login. Exhaustive
  `switch` / `when` over `RegisterResult` must handle the two new cases.
- iOS `extensionSendTimeout` default 5 s → 2 s. The extension persists `DELIVERED`, sends it once and hands the
  notification back when the send ends or the timeout expires; any SDK error hands it back at once.
- Android `handle` gates on the notification's final channel, persists `DELIVERED`, then posts; anything that throws
  before the post ends in a minimal notification (title and body, else the app name), and nothing reaches the host.
  A post that fails after `DELIVERED` was persisted means a missed fallback, not a duplicate one.
- Admin overview and daily statistics gain `noDeviceRegistered` and leave it out of the rate denominators; the circuit
  breaker's cohort leaves it out too.
- `rp_notifications_registered_total` gains the tag `outcome` (`pending`, `no_device_registered`);
  `rp_events_total` gains `token` (`valid`, `grace`).
- Docs: the statement that the SDK never hides a notification is corrected: on Android the SDK posts tracked
  notifications itself (data message → local notification, the market-standard model).

### Fixed

- Upper-case `rp_tid` is acknowledged: the backend echoes tracking ids in lower case, and both SDKs matched the
  response by exact string, so an upper-case id was sent, never acknowledged and dropped as `unacknowledged` after two
  flushes. iOS (`TrackingId.parse`) and Android (`handle`, `trackOpen`) now normalize the id to lower case before it is
  queued (also the `customize` argument and the Android tap extra), and both `EventSender`s match outcomes
  case-insensitively. The payload jar embeds `rp_tid` lower-cased (`ApnsPayloads`, `FcmPayloads`; new
  `*-uppercase-tracking-id` vectors).
- Payload jar: `FcmPayloads.build`, `data` and `decorate` refuse a message with no visible title or body (new code
  `FCM_NO_NOTIFICATION`, like `APNS_NO_ALERT`). A silent data-only message stays untracked instead of becoming a blank
  notification posted by the SDK. The `fcm-decorate-no-notification` vector is now this error.
- iOS extension: a notification settings read that does not answer in time counts as shown and records `DELIVERED`
  (iOS shows the content it gets back); only an actual "not shown" answer records nothing.
- Android `handle`: when something throws before `DELIVERED` was persisted, the SDK is registered and no gate
  answered "not shown", `DELIVERED` is persisted before the minimal notification is posted. The minimal notification
  is gated on its own channel first: when the system would drop it, nothing is posted or recorded and the fallback can
  fire.
- Backend: the active-device check includes the token verifier's 60 s clock skew, so a device counts as active exactly
  as long as its token is accepted on the grace routes. The V7 backfill of `device.token_expires_at` is
  `last_seen_at` + 10 years (a conservative upper bound) instead of + 90 days, so no existing device is wrongly treated
  as inactive.
- iOS: `ReliablePushExtension` reads the stored configuration (App Group, Keychain access group) only in `didReceive`,
  after it has found a valid `rp_tid`. Before, every push the host's Notification Service Extension received, the
  host's own included, opened the App Group and, when it was misconfigured, logged WARN `sdk.app_group_unavailable`.

## 0.1.2 — 2026-11-06

Bug-fix release: one iOS SDK fix and one harness fix. No change to the API or the database.

### Fixed

- iOS SDK: a flush that finds another one running (the app or the Notification Service Extension) now waits for it
  within its own time limit instead of skipping, so a burst of pushes no longer leaves a `DELIVERED` queued until the
  next launch (M7).
- Harness: the device proxy listens on `127.0.0.1` unless `HARNESS_DEVICEPROXYBIND` is set; compose sets `0.0.0.0`
  inside the container, still published on `127.0.0.1:8091` only (M12).

### Process

- New rule `.agents/rules/docs-sync.md`: every change updates the docs, the diagrams and the presentation in the same
  branch.

## 0.1.1 — 2026-11-06

Maintenance release: documentation and dependency updates. No change to the API, the SDK behaviour or the database.

### Documentation

- `docs/overview.md`: Mermaid diagrams of every component, starting from the sidecar principle and a failure table.
- `docs/getting-started.md`: run and test each component step by step.
- `docs/presentation.html`: HTML slides to present the project, with light and dark themes.
- `docs/images/reliable-push-at-a-glance.svg` (and `.png`): the whole system on one picture, in the README.
- `docs/TODO.md`: the remaining work, with why each item waits and what unblocks it.

### Dependencies and tooling

- Root reactor (client, harness, loadtest): JUnit 6.1.3, Maven compiler 3.16.0, install 3.2.0, Surefire and Failsafe
  3.6.1.
- Backend: exec-maven-plugin 3.6.4; swagger-request-validator-core 3.0.0 (tests only).
- CI: `gradle/actions/wrapper-validation` 6.4.1.
- Dependabot: proposes only releases at least two weeks old, and keeps the Temurin images on Java 21 LTS.
- The Helm chart's version and `appVersion` are 0.1.1.

## 0.1.0 — 2026-11-06

First release. reliable-push tracks whether each push notification was delivered and read on the device and, when a
per-notification policy is not met in time, calls an HMAC-signed fallback webhook. It runs next to your existing
backend, which keeps sending the pushes itself.

### Backend (`backend/`)

- Tracking and fallback service: Java 21, Spring Boot 4, PostgreSQL with Flyway. Runs as `api` and `worker` profiles,
  combined or split.
- Sender API: register a notification and get its `trackingId`. Device API: register a device, refresh its token,
  post delivered and read events in batches.
- Fallback worker: leased claims, an HMAC-signed webhook with retries, a terminal outcome for every notification.
- Rate limits, structured ECS logs and metrics. The container image is checked by image tests and a Trivy scan.

### Java sender client (`client-java/`)

- Optional zero-dependency client for the sender API (Java 17+, JDK only).

### Android SDK (`android/`)

- Kotlin, minSdk 26, FCM data messages. Persistent event queue, flushed by a JobScheduler job when the network is back. Does not report
  `DELIVERED` when notifications are blocked. Release AAR under 100 KB.
- Not on Maven Central yet: `./gradlew :reliablepush:publishToMavenLocal` installs it locally. See `docs/android.md`.

### iOS SDK (`ios/`, root `Package.swift`)

- Swift, iOS 15+, Apple frameworks only, distributed through SwiftPM by tag (`from: "0.1.0"`).
- Notification Service Extension helper (`ReliablePushExtension`) records `DELIVERED`. The app records `READ` and
  flushes the queue.
- Keychain device token, App Group storage shared by the app and the extension, cross-process event queue.
- Privacy manifest included. Stripped arm64 binary under 300 KB. See `docs/ios.md`.

### Tooling

- `harness/`: fake sender, fake fallback receiver, device simulator, device proxy, web console (`./scripts/demo.sh`).
  Optional real FCM and APNs senders.
- End-to-end suites, run locally: `./scripts/e2e.sh` (17 scenarios), `./scripts/android-e2e.sh` and
  `./scripts/ios-e2e.sh` (8 scenarios each, plus one optional real-push scenario).
- Helm chart for the backend (`deploy/helm/reliable-push`) with GKE and OpenShift values. Gatling load tests in
  `loadtest/`.
- GitHub Actions: backend, client and harness, Android, iOS, Helm, CodeQL (Java/Kotlin and Swift).

### Known limits

- Real FCM and APNs delivery on a physical device has not been verified in this release: it needs the maintainer's
  Firebase and Apple Developer credentials (see `docs/testing.md`).
- The Android SDK is not published to Maven Central yet.
