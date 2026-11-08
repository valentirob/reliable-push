# Changelog

All notable changes to this project are documented here. Versions follow [Semantic Versioning](https://semver.org/).

## Unreleased

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
  tick (`fallback.circuit_check_failed`).
- Log events `fallback.circuit_opened`, `fallback.circuit_closed`, `fallback.circuit_forced`, `fallback.suppressed`
  (one per tick and app), `fallback.circuit_check_failed`, `admin.fallback_resumed`; metric
  `rp_fallback_suppressed_total{app,reason}` (worker role).
- Database migration `V6__fallback_circuit.sql`: `fallback_circuit` (state per app) and `fallback_circuit_event`
  (history with the detector's counters, kept `admin.rollup-retention-days`).
- Admin API and page: `GET /v1/admin/fallback-circuits`, `GET /v1/admin/fallback-circuits/{appId}/events`,
  `POST /v1/admin/fallback-circuits/{appId}:resume` (new admin key permission `fallback-control`; `FORCED` for
  `force-hold`, 60 min; `409` when suspended or disabled, `503` when the detector holds the lock). The overview shows a
  circuit badge per app, a "Fallback circuit" view shows the history and the "Resume fallbacks" button. The dashboard is
  read-only except this resume (FR-16, FR-17 changed).
- Helm: commented `config.fallback` example in `values.yaml`. The load test runs with the breaker disabled.
- Harness: E2E scenario `fallback-circuit` on a dedicated app `circuit` (the full run is now 19 entries); the E2E stack
  tunes the breaker (window 30 s, `min-due` 10, `close-min-delivered` 3, `min-open` 5 s).
- Docs: circuit breaker section and runbook "circuit open" in `operations.md`; `admin.md`, `api.md`, `security.md`,
  `architecture.md`, `deployment.md`, `integration-guide.md` (senders see `FALLBACK_EXPIRED` during an open circuit),
  `testing.md`, `overview.md`, `requirements.md`, the at-a-glance picture and the presentation.

### Fixed

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
