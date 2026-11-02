# reliable-push

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

reliable-push is an MIT-licensed add-on for teams that already send push
notifications through FCM or APNs. It tracks whether each push was **delivered**
and **read** on the device and, if a per-notification policy is not satisfied
within N minutes, triggers a **fallback** by calling a configurable, HMAC-signed
webhook (`userId`, message, `trackingId`) that routes to SMS, email or any other
channel. It runs as a sidecar: your existing backend keeps sending pushes itself
and keeps the FCM/APNs credentials.

## How it works

1. Your backend registers the notification with `POST /v1/notifications`
   (`userId`, message, policy) using an `X-Api-Key` and receives a `trackingId`.
2. Your backend sends the push through FCM/APNs itself, adding the `trackingId`
   (`rp_tid`) to the payload.
3. The device SDK (iOS Notification Service Extension / Android FCM service)
   reports `DELIVERED` to `POST /v1/events` with its device JWT.
4. When the user taps the notification, the SDK reports `READ`.
5. The reliable-push worker periodically claims notifications that are due and
   whose policy is still unmet, and calls your fallback webhook with a signed
   request (`trackingId`, `userId`, message, `reason`).

## Components

| Path | What | Stack |
|---|---|---|
| `backend/` | Tracking + fallback service (modular monolith, `api`/`worker` profiles) | Java 21, Spring Boot 4, PostgreSQL, Flyway |
| `android/` | Android SDK (FCM v1 data mode + opt-in AndroidConfigV2 mode) | Kotlin, minSdk 26 |
| `ios/` | iOS SDK + Notification Service Extension helper | Swift, iOS 15+, SPM |
| `client-java/` | Optional zero-dependency Java client for the sender API | Java 17+, JDK only |
| `harness/` | Fake sender, fake fallback receiver, device simulator, E2E scenarios, web console for manual tests and demos | Java, plain HTML/JS, docker-compose |
| `docs/` | Requirements, specs, plans, guides, status | Markdown |

Components land incrementally; see [docs/STATUS.md](docs/STATUS.md) for what
exists today.

## Quick start

Coming soon. Step-by-step integration instructions will live in the
[integration guide](docs/integration-guide.md).

## Documentation

- [Requirements](docs/requirements.md)
- [Design spec](docs/specs/2026-11-03-reliable-push-design.md)
- [Architecture](docs/architecture.md)
- [API reference](docs/api.md)
- [Integration guide](docs/integration-guide.md)
- [Security](docs/security.md)
- [Operations](docs/operations.md)
- [Testing](docs/testing.md)
- [Project status](docs/STATUS.md)
- [AGENTS.md](AGENTS.md) (context for AI coding agents)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [License](LICENSE)
