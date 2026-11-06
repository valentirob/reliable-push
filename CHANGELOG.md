# Changelog

All notable changes to this project are documented here. Versions follow [Semantic Versioning](https://semver.org/).

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
