# reliable-push — Design Spec

- **Status:** Approved in conversation 2026-11-03; written spec pending maintainer review.
- **Requirements:** [`../requirements.md`](../requirements.md)
- **Scope of this spec:** all components and their contracts. Implementation runs in cycles, each with its own plan:
  1. Contracts + backend + zero-dependency Java sender client + harness + repo scaffolding (LICENSE, README, docs, CI).
  2. Android SDK.
  3. iOS SDK.

## 1. Architecture

```
Existing notification backend
  │ (1) POST /v1/notifications {userId, message, policy}  ── X-Api-Key ──►  reliable-push API
  │ ◄── 201 {trackingId}
  │ (2) sends push via FCM/APNs with rp_tid = trackingId ─────────────────►  Device
                                                                              │
Device SDK (iOS NSE / Android FCM service)                                    │
  (3) POST /v1/events [{trackingId, DELIVERED, at}]  ── Bearer device JWT ──► API
  (4) POST /v1/events [{trackingId, READ, at}]        (on tap)
                                                                              
reliable-push worker
  (5) every few seconds: claim PENDING rows with due_at <= now (SKIP LOCKED)
      ──► POST <app fallback webhook> {trackingId, userId, message, reason}  (HMAC-signed)
```

**Shape:** a modular monolith (one Spring Boot application, one container image).
Spring profiles select the role: `api` (HTTP endpoints), `worker` (scheduler +
webhook dispatcher), or both (default). PostgreSQL is the only stateful
dependency. Integration is **sidecar**: the existing backend keeps sending pushes
and holds the FCM/APNs credentials; reliable-push never does.

**Tenancy:** multi-app, single organisation. Every row is partitioned by `appId`.

### 1.1 Backend modules (`backend/src/main/java/io/reliablepush/...`)

| Module | Responsibility | Public surface |
|---|---|---|
| `apps` | Per-app configuration (keys, OIDC, defaults, webhook) loaded from config | `AppRegistry.find(appId)` |
| `notifications` | Sender API: register, get, cancel | REST `/v1/notifications`, `NotificationService` |
| `devices` | Device registration, device-JWT issue/refresh/revoke, OIDC verification | REST `/v1/devices`, `DeviceTokenService`, `IdentityVerifier` SPI |
| `tracking` | Device events → state transitions | REST `/v1/events`, `TrackingService` |
| `fallback` | Due-row claiming, webhook dispatch, retries | `FallbackScheduler`, `WebhookClient` |
| `shared` | Errors, clock, ids, rate limiting, security filters | — |

Modules call each other only through the listed services, never through
another module's repositories.

## 2. Notification lifecycle

States: `PENDING`, `DELIVERED`, `READ`, `FALLBACK_TRIGGERED`, `FALLBACK_FAILED`, `FALLBACK_EXPIRED`, `CANCELLED`, plus the internal `DISPATCHING` (fallback claimed, webhook in progress or awaiting retry; reported to senders as `FALLBACK_PENDING`).

```
PENDING ──DELIVERED event──► DELIVERED ──READ event──► READ
PENDING ──READ event───────► READ                (READ implies delivered; delivered_at = read_at if unset)
PENDING / DELIVERED ──due & policy unmet──► (dispatching) ──2xx──► FALLBACK_TRIGGERED
                                                        ├─retries exhausted─► FALLBACK_FAILED
                                                        └─trigger window passed─► FALLBACK_EXPIRED
PENDING / DELIVERED ──claimed after trigger window passed──► FALLBACK_EXPIRED   (never dispatched)
PENDING / DELIVERED ──sender cancel──► CANCELLED
```

- **Policy** `NOT_DELIVERED`: satisfied by `DELIVERED` or `READ`. Policy `NOT_READ`: satisfied only by `READ`.
- **Due time:** `due_at = created_at + timeoutMinutes`. Rows whose policy is satisfied get `due_at = NULL` (removed from the partial index).
- **Race safety:** events update with `UPDATE ... WHERE id = ? AND state IN (<allowed>)`; the worker claims rows with `SELECT ... FOR UPDATE SKIP LOCKED LIMIT n` and sets `state = 'DISPATCHING'` in the same transaction. Exactly one of "policy satisfied" and "fallback claimed" wins.
- **Claim query:** `(state IN ('PENDING','DELIVERED') AND due_at <= now()) OR (state = 'DISPATCHING' AND next_attempt_at <= now())`. On claim, `next_attempt_at = now() + lease` (default 2 min) so a worker that crashes mid-dispatch releases the row automatically; on webhook failure `next_attempt_at = now() + backoff`; on success or final failure the row is closed. A crash after the webhook succeeded but before commit can re-send once; receivers de-duplicate with `Idempotency-Key`.
- **Trigger window (stale-fallback guard):** a fallback may only be dispatched while `now() <= due_at + triggerWindowMinutes` (per app, default 20; must be ≥ the cumulative retry backoff). This applies to every attempt — the first one, retries, and re-claims after a worker crash or outage. A row claimed outside the window is closed as `FALLBACK_EXPIRED` without calling the webhook, and logged as `fallback.expired`. Retries whose next backoff would fall outside the window are not scheduled; the row expires instead. The original `due_at` is kept (not moved by retries) so the window is measured from when the trigger should have started.
- **Late events** (after fallback claimed) are stored in `notification_event` with `late = true`; the state does not change.
- **Duplicates** (same `trackingId` + type) are no-ops returning success.
- **Multi-device:** events from any active device of the same `appId` + `userId` count.

## 3. Data model (PostgreSQL, Flyway)

```sql
notification(
  id uuid pk,                       -- trackingId
  app_id text not null,
  user_id text not null,
  external_id text null,            -- sender's own id, unique per app when present
  policy text not null,             -- NOT_DELIVERED | NOT_READ
  timeout_minutes int not null,
  fallback_payload jsonb not null,  -- message for the fallback channel (opaque to us, size-bounded)
  state text not null,
  created_at timestamptz not null,
  due_at timestamptz null,
  delivered_at timestamptz null,
  read_at timestamptz null,
  fallback_attempts int not null default 0,
  next_attempt_at timestamptz null,
  closed_at timestamptz null
)
index notification_due on notification(due_at) where due_at is not null
unique (app_id, external_id) where external_id is not null

notification_event(id bigserial, notification_id uuid, device_id uuid, type text, occurred_at timestamptz, received_at timestamptz, late bool)
device(id uuid pk, app_id, user_id, platform text, token_version int, created_at, last_seen_at, revoked_at null)
```

Retention: a daily job deletes closed notifications and their events older than
`retentionDays` (default 30).

## 4. APIs (OpenAPI 3, contract-first in `backend/src/main/resources/openapi.yaml`)

### 4.1 Sender API — header `X-Api-Key` (per app, stored as SHA-256 hash)

| Method | Path | Body / result |
|---|---|---|
| `POST` | `/v1/notifications` | `{userId, externalId?, policy?, timeoutMinutes?, fallback:{...}}` → `201 {trackingId, dueAt}` |
| `GET` | `/v1/notifications/{trackingId}` | → `{state, createdAt, deliveredAt, readAt, ...}` |
| `DELETE` | `/v1/notifications/{trackingId}` | cancel → `204` (409 if already closed) |

`appId` comes from the API key, never from the body. `POST` with an existing
`externalId` returns the existing record (idempotent).

### 4.2 Device API

| Method | Path | Auth | Body / result |
|---|---|---|---|
| `POST` | `/v1/devices` | OIDC ID token in body | `{appId, idToken, platform, deviceId?}` → `201 {deviceId, deviceToken, expiresAt}` |
| `POST` | `/v1/devices/token:refresh` | device JWT | → `{deviceToken, expiresAt}` |
| `DELETE` | `/v1/devices/me` | device JWT | revoke → `204` |
| `POST` | `/v1/events` | device JWT | `{events:[{trackingId, type, occurredAt}]}` (max 50) → `200 {accepted, ignored}` |

### 4.3 Errors
RFC 9457 problem+json. `401` invalid credentials, `403` trackingId of another
user/app, `404` unknown id, `409` invalid state, `413`/`422` validation, `429`
rate limited with `Retry-After`.

## 5. Security

- **Device identity:** `IdentityVerifier` SPI. Default `OidcIdTokenVerifier` validates signature (issuer JWKS, cached), `iss`, `aud`, `exp`, and maps a configured claim (default `sub`) to `userId`. Alternative `SignedGrantVerifier`: short-lived JWT signed by the customer backend (shared HMAC secret or public key).
- **Device JWT:** ES256, signed with a backend key (key id rotation via JWKS-style config). Claims: `iss`, `aud=appId`, `sub=userId`, `did=deviceId`, `ver=token_version`, `iat`, `exp`. Validity per app (default 90 days). Revocation: `device.revoked_at` or bumped `token_version`, checked with a short in-memory cache.
- **Event authorisation:** `notification.app_id = jwt.aud AND notification.user_id = jwt.sub`, else `403` (counted, not detailed).
- **Rate limiting:** Bucket4j in-memory token buckets per device and per app API key (defaults: device 60 req/min, sender key 6,000 req/min). Interface allows a shared store later.
- **Webhook:** headers `X-RP-Timestamp`, `X-RP-Signature: v1=<hex HMAC-SHA256(secret, timestamp + "." + body)>`, `Idempotency-Key: <trackingId>`. Retries: max 5 attempts with exponential backoff between them (1, 2, 4, 8 min), configurable; per-attempt HTTP timeout 10 s. Only `2xx` is success; `4xx` other than `408/429` is final failure.
- **Hygiene:** Bean Validation on all inputs, `fallback` payload ≤ 4 KB, request body ≤ 64 KB, no tokens or payloads in logs, TLS terminated at ingress.

## 6. Push payload contract (customer backend → device)

The tracking id key is `rp_tid` (string UUID). Policy and timeout are not sent to the device.

- **iOS (APNs):** `aps.alert` present **and** `aps.mutable-content = 1`; `rp_tid` at the top level of the payload.
- **Android, mode v1 (default, GA):** data-only message (`data.rp_tid`, `data.title`, `data.body`, optional `data.*`), `android.priority = HIGH`. The SDK reports `DELIVERED` and **always** posts a visible notification.
- **Android, mode v2 (opt-in):** `AndroidConfigV2` `RemoteNotification` with `mutable_content: true` and `rp_tid` in its data; SDK implements `onNotificationReceived` and returns a `NotificationResponse` within 20 s (requires firebase-messaging ≥ 26.1.0-eap01; older devices fall back to v1 `onMessageReceived`).

Pushes sent in another shape are not tracked → they end in fallback (by design: "unknown" ⇒ fallback).

## 7. Mobile SDKs (cycles 2 and 3)

### 7.1 Common API
- `configure(baseUrl, appId)`
- `register(idToken)` — on login / app open; stores device token securely.
- `handle(message)` — called from the push entry point; returns quickly.
- `trackOpen(notification)` — called on tap.
- `logout()` — revokes and clears.

Token logic: decode `exp` locally; refresh when remaining < 20% of validity or
on `401`; never refresh more than once per app launch. Events that fail to send
are persisted and retried in a batch (Android `JobScheduler` with network
constraint; iOS on next app launch or next NSE run). No polling, no periodic jobs.
SDK logging uses the platform logger (`android.util.Log` / `OSLog`) with the same
event names as the backend (e.g. `tracking.event_sent`, `token.refreshed`),
off below WARN in release builds, never logging tokens or notification content.

### 7.2 Android (`android/`, Kotlin, minSdk 26, AAR on Maven Central)
- `ReliablePushMessagingService` helper delegating from the host's `FirebaseMessagingService` (v1) and `onNotificationReceived` (v2).
- Storage: device token encrypted with an Android Keystore AES-GCM key, stored in private SharedPreferences (no `androidx.security-crypto`).
- Pending-event retry via platform `JobScheduler` with a network constraint (no WorkManager); HTTP via `HttpURLConnection`, JSON via `org.json`.
- Respects `POST_NOTIFICATIONS` (Android 13+); documents Play Data Safety entries.

### 7.3 iOS (`ios/`, Swift, iOS 15+, Swift Package)
- `ReliablePushExtension` helper for the host's `UNNotificationServiceExtension`: reads `rp_tid`, sends `DELIVERED` with a short timeout, always calls the content handler (also from `serviceExtensionTimeWillExpire`).
- Device token in a shared Keychain access group + App Group so app and NSE share it.
- `READ` from `UNUserNotificationCenterDelegate.didReceive(response:)`.
- Ships `PrivacyInfo.xcprivacy`; documents App Store privacy labels.

Known limits (documented): no tracking if the user disabled notifications, the
Android app was force-stopped, or the device lacks Google Play services; all end
in fallback.

## 8. Configuration (example)

```yaml
reliable-push:
  device-token:
    signing-key-ref: env:RP_DEVICE_JWT_KEY   # PEM, ES256
  worker:
    poll-interval: 5s
    batch-size: 100
  retention-days: 30
  apps:
    shop:
      sender-api-key-sha256: "<hash>"
      identity: { type: oidc, issuer: "https://idp.example.com", audience: "shop-mobile", user-claim: sub }
      device-token-validity: 90d
      default-policy: NOT_DELIVERED
      default-timeout-minutes: 10
      fallback: { url: "https://fallback.example.com/hook", hmac-secret-ref: env:RP_SHOP_HOOK_SECRET, max-attempts: 5, trigger-window-minutes: 20 }
```

## 9. Operations

- Java 21 (virtual threads), Spring Boot 4.x, Maven wrapper, Flyway, Actuator (`/actuator/health/{liveness,readiness}`), Micrometer Prometheus.
- Metrics: `rp_notifications_registered_total`, `rp_events_total{type,late}`, `rp_fallback_total{outcome=triggered|failed|expired}`, `rp_webhook_latency_seconds`, `rp_due_backlog`.

### 9.1 Logging

- **Framework:** Log4j 2 via `spring-boot-starter-log4j2` (Logback excluded); application code logs through the Log4j 2 API (or SLF4J bound to Log4j 2). Async loggers are not required; keep the default synchronous appender unless load tests show otherwise.
- **Format:** profile-selected in `log4j2-spring.xml`:
  - `json` (default in containers): one JSON object per line on stdout, `JsonTemplateLayout` with the bundled **Elastic Common Schema** template (`EcsLayout.json`), ready for Cloud Logging, OpenShift logging / EFK, Loki.
  - `local`: `PatternLayout` `%d{ISO8601} %-5level [%t] %c{1.} [trace=%X{trace.id}] - %m%n%throwable`.
- **Correlation:** a servlet filter reads the W3C `traceparent` header (or `X-Request-Id`), otherwise generates an id, puts it in the `ThreadContext` (`trace.id`) and returns it as `X-Request-Id`. The worker sets a new `trace.id` per claimed row and adds `rp.tracking_id`, so one notification can be followed across API and worker logs.
- **Common fields** (ECS names, plus an `rp.*` namespace): `@timestamp`, `log.level`, `log.logger`, `message`, `service.name`, `service.version`, `trace.id`, `event.action`, `event.outcome` (`success|failure|unknown`), `event.duration` (ns), `http.request.method`, `url.path` (template, e.g. `/v1/notifications/{id}`), `http.response.status_code`, `rp.app_id`, `rp.tracking_id`, `rp.device_id`, `rp.user_hash` (truncated SHA-256 of `userId`; raw user ids are not logged).
- **Never logged:** API keys, ID tokens, device JWTs, HMAC secrets, webhook signatures, fallback message content, request/response bodies.
- **Event catalogue** (`event.action`; one INFO line each unless noted):

| Event | Emitted when |
|---|---|
| `api.request` | every HTTP request completes (access log: method, path template, status, duration, app, outcome) |
| `notification.registered` / `notification.duplicate` / `notification.cancelled` | sender API |
| `device.registered` / `device.token_refreshed` / `device.revoked` | device API |
| `tracking.event_accepted` (with `rp.event_type`, `rp.late`) / `tracking.event_ignored` (duplicate) | `/v1/events` |
| `auth.failed` (WARN, reason code only) / `authz.denied` (WARN) / `rate_limit.exceeded` (WARN) | security filters |
| `fallback.claimed` / `fallback.dispatched` / `fallback.retry_scheduled` / `fallback.failed` (WARN) / `fallback.expired` (WARN) / `fallback.reclaimed` (WARN, lease expired → previous worker crashed) | worker |
| `retention.purged` | retention job (count only) |

- Levels: INFO for the catalogue, WARN for security and degraded outcomes, ERROR only for unexpected exceptions (with stack trace), DEBUG off by default. Log levels configurable at runtime via environment (`LOGGING_LEVEL_*`).

### 9.2 Dependency budget ("lightweight")

The backend, the sender client and the mobile SDKs avoid heavy frameworks. Any new dependency needs a justification in the PR and must fit this budget.

| Component | Allowed | Not allowed |
|---|---|---|
| Backend service | Spring Boot web (embedded Tomcat), Spring JDBC (`JdbcClient`), Actuator + Micrometer Prometheus, Flyway, PostgreSQL driver, Log4j 2 (+ `log4j-layout-template-json` for ECS), Bean Validation, `nimbus-jose-jwt` (JWT/JWKS), Bucket4j core | JPA/Hibernate, Spring Security, Spring Cloud, Lombok, MapStruct, reactive stack, message brokers |
| Sender client `client-java/` (optional helper for existing backends) | JDK 17+ only (`java.net.http.HttpClient`), hand-written JSON for its fixed small schema | any runtime dependency |
| Android SDK | Kotlin stdlib, Android platform APIs (`HttpURLConnection`, `org.json`, Keystore AES-GCM, `JobScheduler`); `firebase-messaging` as `compileOnly` (host app provides it) | OkHttp/Retrofit, coroutines, WorkManager, DI frameworks, Gson/Moshi, `androidx.security-crypto` (deprecated) |
| iOS SDK | Foundation, UserNotifications, Security (Keychain), OSLog | any third-party package |

Size targets (checked in CI from cycle 2/3): Android AAR < 100 KB, iOS framework < 300 KB; backend container image < 250 MB.

Authentication filters are implemented as small servlet filters on top of `nimbus-jose-jwt` instead of Spring Security; they are covered by dedicated unit and integration tests and by the security reviewer role.
- Container: distroless or UBI-minimal JRE, non-root, writable dirs only under `/tmp`, works with OpenShift arbitrary UIDs.
- Deploy: Helm chart (`deploy/helm/reliable-push`) with values for GKE and OpenShift (Route instead of Ingress, no fixed UID); separate `api`/`worker` deployments optional.

## 10. Testing strategy

| Level | Tooling | Covers |
|---|---|---|
| Unit | JUnit 5, AssertJ | state machine, policy evaluation, token logic, HMAC, validation |
| Integration (`*IT`) | Testcontainers PostgreSQL, WireMock (OIDC JWKS, webhook) | REST endpoints, SKIP LOCKED claiming with concurrent workers, retries, rate limits |
| Contract | OpenAPI validation of requests/responses in ITs | API drift |
| End-to-end | `harness/` + docker-compose | full scenarios across components |
| Load | Gatling (cycle 1, basic) | NFR-2 target |
| Mobile (cycles 2–3) | JUnit/Robolectric, XCTest; emulator/simulator instrumented tests against the harness | SDK logic, NSE/service handling |

**Harness (`harness/`):** fake OIDC issuer (issues test ID tokens + JWKS), fake
sender (registers notifications), fallback receiver (verifies HMAC, records
calls), device simulator (registers devices, sends DELIVERED/READ with
configurable delay/loss), and a scenario runner that asserts outcomes:
delivered before timeout, read policy, not delivered → exactly one fallback,
late delivery, multi-device, cancellation, token refresh near expiry, rate
limiting, worker crash during dispatch inside the trigger window (re-sent once)
and outside it (`FALLBACK_EXPIRED`, webhook not called), and log assertions
(every API call and trigger step produces its catalogue event; no secrets in logs).

## 11. SDLC

- Gitflow (`.agents/rules/gitflow.md`), Conventional Commits, SemVer tags on `main`.
- GitHub Actions: on PR/push — build, unit + IT tests, JaCoCo coverage (≥ 80% line on backend), CodeQL, dependency review/OWASP check, container build + Trivy scan; E2E harness job on `develop`/`release/*`. Mobile jobs added in cycles 2–3.
- Multi-agent review: `.agents/skills/review-loop` (correctness, security, tests, mobile policy) run before merging feature branches; Claude subagents in `.claude/agents/`, any other tool runs the roles from the skill.
- Continuity: `AGENTS.md`, `.agents/rules`, `.agents/skills`, `docs/STATUS.md` updated each session.

## 12. Repository layout (end state)

```
LICENSE  README.md  AGENTS.md  CLAUDE.md  CONTRIBUTING.md  SECURITY.md
.agents/{rules,skills}/   .claude/{agents,skills->../.agents/skills}
.github/workflows/
backend/      client-java/      harness/      android/      ios/      deploy/helm/
docs/  requirements.md  STATUS.md  specs/  plans/  architecture.md  api.md
       integration-guide.md  security.md  operations.md  testing.md
```
