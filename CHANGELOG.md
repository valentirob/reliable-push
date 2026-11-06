# Changelog

All notable changes to this project are documented here. Versions follow [Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- Ops dashboard (0.2.0, FR-16, FR-17): a read-only page at `/admin/` and API at `/v1/admin` served by the backend, enabled
  only when an admin key is configured (otherwise both answer `404` like an unknown path). Views: overview with
  delivery, read and fallback rates and the due backlog; daily trends up to 400 days; search by user, external id,
  tracking id, state and time; notification timeline with events and webhook attempts; a user's devices.
- Admin keys in `reliable-push.admin.keys[]` (stored as SHA-256, scoped to all apps or a list, optional `payload`
  permission), rate limit `admin.rate-limit-per-minute`, audit events `admin.search`, `admin.notification_viewed`,
  `admin.payload_viewed`.
- Database migration `V4__admin.sql`: `fallback_attempt` (one row per webhook call) and `daily_stats` (nightly rollup
  by `DailyRollupJob`, kept `admin.rollup-retention-days`, 400 by default). `RetentionJob` skips its purge when the
  rollup is missing (`retention.skipped`).
- Helm: `config.admin` in `values.yaml`.
- Harness: E2E scenario `admin-dashboard` and `AdminSmokeE2E` (the full run is now 18 entries); a per-run admin key.
- Docs: `docs/admin.md`, admin sections in `api.md`, `security.md`, `deployment.md`, `testing.md`, a new section in
  `overview.md`, the at-a-glance picture and a slide.

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
