# Requirements — reliable-push

Source of truth for **what** the product must do. The **how** lives in
[`specs/2026-11-03-reliable-push-design.md`](specs/2026-11-03-reliable-push-design.md).
Requirement ids (`FR-x`, `NFR-x`) are referenced by plans, tests and reviews.

## Purpose

Teams that already send push notifications (Firebase Cloud Messaging, APNs) do
not know whether a push actually reached the user. reliable-push adds delivery
and read tracking on top of an existing push stack and triggers a server-side
fallback (SMS, email, any channel) when a push is not confirmed in time.

License: MIT. Audience: backend and mobile teams of one organisation running
one or more apps.

## Functional requirements

### Notification tracking (sender side)
- **FR-1** An existing notification backend registers each push it sends via an API, receiving (or supplying) a `trackingId` to embed in the push payload. reliable-push does not send pushes itself.
- **FR-2** Each registration carries `appId`, `userId`, the fallback message content, and a fallback policy: condition `NOT_DELIVERED` or `NOT_READ`, and a timeout in minutes (default per app).
- **FR-3** The sender can cancel a pending notification and query its status.

### Device side
- **FR-4** The mobile SDK registers the device when the user logs in or opens the app, proving user identity with the app's existing OIDC ID token (pluggable verifier; signed grant as alternative).
- **FR-5** The backend issues a long-lived device token (default 90 days, configurable per app). The SDK validates expiry locally and renews only when near expiry (default: < 20% validity left).
- **FR-6** The SDK reports `DELIVERED` when a tracked push arrives, **including when the app is not running** (iOS Notification Service Extension; Android FCM service).
- **FR-7** The SDK reports `READ` when the user opens the notification.
- **FR-8** The SDK unregisters the device on logout.

### Fallback
- **FR-9** If the policy condition is still unmet at `sentAt + timeout`, the backend calls the app's configured fallback webhook once with `userId`, message, `trackingId` and reason.
- **FR-10** A confirmation from any active device of the user satisfies the policy.
- **FR-11** Events arriving after the fallback fired are recorded but do not revert it.
- **FR-12** Webhook calls are signed, idempotent (`Idempotency-Key`) and retried with backoff; exhausted retries end in `FALLBACK_FAILED` and are observable.
- **FR-12a** A fallback (first attempt, retry, or recovery after a worker crash/outage) is dispatched only within a configurable trigger window after its scheduled time (default 20 minutes). Outside the window it is not sent and ends in `FALLBACK_EXPIRED`.

### Multi-app
- **FR-13** One deployment serves several apps of one organisation, each with its own sender API key, OIDC issuer/audience, device-token validity, default timeout and fallback webhook.

### Testing and demo
- **FR-14** A local web console (part of the test harness, never deployed to production) lets a person create simulated devices, send simulated pushes with configurable device behaviour, watch delivery/read/fallback status live, inspect fallback webhook calls and logs, and run E2E scenarios — for manual testing and demos.
- **FR-15** The sender API offers a paginated list of notifications per app (filter by user, state, time) in addition to lookup by `trackingId`.

## Non-functional requirements

- **NFR-1 Security** All APIs authenticated (sender API key; device JWT). Events accepted only for `trackingId`s of the same app and user as the device token. Rate limiting per device and per app. No secrets in the repo.
- **NFR-2 Performance** Lightweight: the device side makes at most one call per push event plus rare token renewals. Target ≥ 1,000 events/s per backend replica. **Validated (plan 1C, 2026-11-05): passed**: one API replica limited to 2 CPUs / 1 GiB, criterion and results in [operations.md, "Measured capacity"](operations.md#measured-capacity) (local load test `./scripts/load.sh`).
- **NFR-3 Reliability** Survives restarts; runs as multiple replicas without double-firing fallbacks.
- **NFR-4 Portability** Runs on Google Cloud GKE and Red Hat OpenShift (non-root, arbitrary UID), PostgreSQL as the only stateful dependency.
- **NFR-5 Store compliance** SDKs follow Apple App Store and Google Play policies: short work in extensions/services, no hidden background activity, minimal data, privacy manifests and documented data collection.
- **NFR-6 Testability** Unit, integration and contract tests run automatically in CI. End-to-end tests run on a local PC with one command, started manually or by an AI agent in its feedback loop, against a harness that simulates sender, fallback receiver and devices; results are machine-readable.
- **NFR-7 Observability** Health endpoints, Prometheus metrics (events, fallbacks, webhook outcomes, latency). Logging with Log4j 2 in structured JSON (Elastic Common Schema) with correlation ids; every API call and every trigger step emits a catalogued log event; no tokens, keys, user ids or message bodies in logs.
- **NFR-10 Lightweight** Backend, sender client and mobile SDKs avoid heavy frameworks and stay within the dependency budget of the spec (§9.2): no ORM, no Spring Security, zero third-party runtime dependencies in the sender client and iOS SDK, platform APIs only in the Android SDK besides Kotlin stdlib.
- **NFR-8 Continuity** Repo carries LLM-agnostic context (`AGENTS.md`, rules, skills, `docs/STATUS.md`) so work can continue with Claude Code, Antigravity or other tools.
- **NFR-9 SDLC** Gitflow, Conventional Commits, CI with build, tests, coverage, static/security analysis, container build; multi-agent review loop before merge.

## Out of scope (for now)

- Sending pushes on behalf of the customer (proxy mode).
- Direct integrations with SMS/email providers (the webhook decouples them; the harness ships a reference receiver).
- Devices without Google Play services (e.g. Huawei HMS), web push.
- Full multi-tenant SaaS isolation and admin UI.
