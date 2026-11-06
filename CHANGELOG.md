# Changelog

All notable changes to this project are documented here. Versions follow [Semantic Versioning](https://semver.org/).

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
