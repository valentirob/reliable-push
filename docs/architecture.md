# Architecture

reliable-push is one Spring Boot application (a modular monolith) in one container image. PostgreSQL is its only
stateful dependency. The design spec ([`specs/2026-11-03-reliable-push-design.md`](specs/2026-11-03-reliable-push-design.md))
is the source of truth for decisions; this page explains how the backend implements them.

## Roles and profiles

Spring profiles select the background work of an instance:

| Profile | What it enables |
|---|---|
| `worker` | Fallback scheduler, webhook client, retention job, due-backlog gauge |
| `api` | Marks an API-only instance (activate it without `worker`); the HTTP endpoints are served by every instance |
| `api,worker` (default when no profile is set) | Both |

The `json` / `local` profiles only switch the log layout; `e2e` and `demo` enable the fast clock. See
[operations.md](operations.md). Replicas of either role can be run side by side: coordination happens through
PostgreSQL row locks, not through any in-process state (rate limits and the revocation cache are per instance).

```mermaid
flowchart LR
  SB["Sender backend"] -- "POST /v1/notifications<br/>(X-Api-Key)" --> API["api"]
  SDK["Device SDK"] -- "POST /v1/devices<br/>(OIDC ID token)" --> API
  SDK -- "POST /v1/events<br/>(device JWT)" --> API
  API --> DB[("PostgreSQL<br/>Flyway V1..V3 at startup")]
  WRK["worker"] --> DB
  WRK -- "signed POST" --> FW["Fallback webhook"]
```

The backend is a sidecar: it never sends pushes and is not on the delivery path, so an outage pauses tracking and
fallbacks but not your notifications ([overview.md](overview.md#1-the-sidecar-principle)).

## Modules

Package `io.reliablepush`; modules call each other through public services only, never through another module's
repositories.

| Module | Responsibility | Entry points |
|---|---|---|
| `apps` | Typed `reliable-push.*` configuration, validated once at startup; per-app registry | `AppRegistry`, `ReliablePushProperties` |
| `notifications` | Sender API: register (idempotent per `externalId`), get, list, cancel; repository with all state transitions; retention and backlog jobs | `NotificationController`, `NotificationService`, `NotificationRepository`, `RetentionJob`, `DueBacklogJob` |
| `devices` | Device registration, device-token issue / refresh / revoke, signing keys, identity verification | `DeviceController`, `DeviceService`, `DeviceTokenService`, `SigningKeys`, `identity/*` |
| `tracking` | Device events to state transitions, per-item outcomes | `EventsController`, `TrackingService` |
| `fallback` | Claiming loop, trigger-window decision, signed webhook client | `FallbackScheduler`, `FallbackDispatcher`, `FallbackDecision`, `HttpWebhookClient`, `WebhookSigner` |
| `shared` | Servlet filter chain (trace id, access log, body limit, sender key auth, device auth, rate limit), problem+json errors, logging (`EventLog`), metrics (`RpMetrics`), clock and `TimeScale`, secret references | `shared/*` |

There is no ORM and no Spring Security: persistence is plain SQL through `JdbcClient`, authentication is a pair of small
servlet filters on top of `nimbus-jose-jwt`.

### Request filter chain

In order: `TraceIdFilter` (correlation id, `X-Request-Id`) -> `AccessLogFilter` (`api.request`) -> `BodySizeLimitFilter`
(64 KB on `/v1/**`) -> `SenderApiKeyFilter` for `/v1/notifications/**` or `DeviceAuthFilter` for
`/v1/devices/token:refresh`, `/v1/devices/me` and `/v1/events` -> `RateLimitFilter` (per sender app or per device).
`POST /v1/devices` is not behind an authentication filter: it proves identity with the app's credential inside the
request body and is rate limited per user after that check.

## Notification lifecycle

```mermaid
stateDiagram-v2
  [*] --> PENDING: registered
  PENDING --> DELIVERED: DELIVERED event
  PENDING --> READ: READ event (implies delivered)
  DELIVERED --> READ: READ event
  PENDING --> DISPATCHING: due_at reached, policy unmet
  DELIVERED --> DISPATCHING: due_at reached, policy unmet
  DISPATCHING --> DISPATCHING: retryable failure, next_attempt_at = now + backoff
  DISPATCHING --> FALLBACK_TRIGGERED: webhook 2xx
  DISPATCHING --> FALLBACK_FAILED: final 4xx, or max-attempts exhausted
  DISPATCHING --> FALLBACK_EXPIRED: next retry would fall after the window
  PENDING --> FALLBACK_EXPIRED: claimed after due_at + trigger window (webhook not called)
  DELIVERED --> FALLBACK_EXPIRED: claimed after due_at + trigger window (webhook not called)
  PENDING --> CANCELLED: sender DELETE
  DELIVERED --> CANCELLED: sender DELETE
```

`DISPATCHING` is reported as `FALLBACK_PENDING` by the API.

`READ`, `FALLBACK_TRIGGERED`, `FALLBACK_FAILED`, `FALLBACK_EXPIRED` and `CANCELLED` are final. Under policy
`NOT_DELIVERED` a `DELIVERED` row is already satisfied: `due_at` is cleared and `closed_at` set, but it can still become
`READ`. Under `NOT_READ` it stays due until `READ`.

- **Policy:** `NOT_DELIVERED` is satisfied by `DELIVERED` or `READ`; `NOT_READ` only by `READ`. Policy and timeout are
  never sent to the device.
- **`due_at`:** `created_at + timeoutMinutes`. It is never moved by retries.
- **`closed_at`:** the moment no further fallback processing is needed (policy satisfied, `READ`, `CANCELLED` or a final
  fallback state). A closed row has no `due_at` (CHECK constraint `notification_closed_not_due`), so the partial index on
  `due_at` only ever contains work for the worker. The retention job deletes by `closed_at`.
- **Events:** one row per `(notification, type)` (`UNIQUE`, `INSERT ... ON CONFLICT DO NOTHING`), so a duplicate is a
  no-op reported as `DUPLICATE`. State changes are conditional updates (`WHERE state IN (...)`). An event whose transition
  no longer applies (fallback already claimed, cancelled, already read) is stored with `late = true` and reported as
  `LATE`; the state does not change. Events from any active device of the same app and user count. State decisions use the
  server receive time; the device's `occurredAt` is stored but clamped to `[received - 7 days, received]`.

## Claiming, lease and trigger window

### Claim

Every `poll-interval` (default 5 s) a tick of the scheduler claims due rows, each claim one statement in one transaction
taking at most `min(batch-size, free slots)` rows (at most `max-concurrency` webhook calls are in flight). The tick keeps
claiming while claims come back full, for at most half the lease, so a backlog drains at the receivers' speed:

```sql
with due as (
  select id, state as previous_state, lease_until is not null as reclaimed
    from notification
   where (state in ('PENDING','DELIVERED') and due_at <= :now)
      or (state = 'DISPATCHING' and next_attempt_at <= :now)
   order by coalesce(next_attempt_at, due_at)
   limit :batch
     for no key update skip locked)
update notification n set state = 'DISPATCHING', next_attempt_at = :leaseUntil, lease_until = :leaseUntil
  from due where n.id = due.id returning ...
```

- `SKIP LOCKED` means replicas never claim the same row; `FOR NO KEY UPDATE` does not block event inserts that reference
  the row, so a device event racing with the claim is decided by the conditional updates: exactly one of "policy
  satisfied" and "fallback claimed" wins.
- Each claimed row is processed on its own virtual thread, outside any transaction (no row lock is held during the HTTP
  call); a new claim starts as soon as a slot is free, and the tick returns when all its rows are done (ticks of one
  instance never overlap). Each row gets a fresh `trace.id` and `rp.tracking_id`.

### Lease

A claim sets `lease_until = next_attempt_at = now + lease` (default 2 min). If the worker dies, the row becomes claimable
again when the lease ends. The worker persists its outcome (retry scheduled, or a final state) with an update **fenced** on
the `lease_until` it claimed with, plus `state = 'DISPATCHING'` and the attempts count it saw. If another worker re-claimed
the row in the meantime, the stale worker's update matches nothing and its outcome is dropped (logged as a WARN, not
counted in metrics). A row is **reclaimed** when it is claimed while `lease_until` is still set; this is logged as
`fallback.reclaimed`.

Consequences:

- The lease must outlast one webhook call, so it must be longer than `fallback.http-timeout`. This is checked at startup
  (an error outside the fast clock, a warning with it).
- A crash after the webhook accepted the call but before the outcome was written re-sends once. Such re-sends are not
  counted against `max-attempts` (attempts are persisted with an outcome). Receivers must therefore de-duplicate on
  `Idempotency-Key` ([integration-guide.md](integration-guide.md)).
- `V3` (`lease_until`) is the only schema change that older code does not know; see mixed-version rollouts in
  [operations.md](operations.md).

### Trigger window (stale-fallback guard)

A fallback is only dispatched while `now <= due_at + trigger-window-minutes` (per app, default 20; startup requires it to
be at least the cumulative backoff of `max-attempts` attempts). The check applies to the first attempt, to retries and to
re-claims after a crash or an outage.

| Situation | Result |
|---|---|
| Claimed after the window | `FALLBACK_EXPIRED` without calling the webhook, `fallback.expired` (`trigger_window_passed`) |
| Retryable failure, next retry would be after the window | `FALLBACK_EXPIRED` (`next_retry_after_trigger_window`) |
| Webhook `2xx` | `FALLBACK_TRIGGERED`, even if the window ended during the call (the attempt started inside it) |
| `4xx` other than `408`/`429` | `FALLBACK_FAILED` (final) |
| `408`, `429`, `5xx`, redirect, timeout, I/O error | retry with the app's backoff (default 1, 2, 4, 8 min) until `max-attempts`, then `FALLBACK_FAILED` |
| Row of an app not in this instance's configuration | left for later; closed as `FALLBACK_EXPIRED` (`app_not_configured`) after `due_at` + 20 min |

With the default 5 attempts and backoff the cumulative backoff is 15 min against a 20 min window, which leaves a margin of
5 min; retries that would land after the window end expire instead of firing, so keep a margin when changing either value.

## Webhook

One signed JSON POST per attempt: body `{trackingId, userId, fallback, reason, attempt}`, headers `X-RP-Timestamp`,
`X-RP-Signature` (`v1=` + hex HMAC-SHA256 of `timestamp + "." + body`), `Idempotency-Key` (the `trackingId`), `User-Agent:
reliable-push/<version>`. Redirects are not followed, the response body is discarded, and `http-timeout` bounds the whole
exchange (connect, headers and body). Details for receivers: [integration-guide.md](integration-guide.md).

## Device identity and tokens

`POST /v1/devices` verifies the app's identity credential (an OIDC ID token, or an HS256 signed grant) and issues an ES256
device token (`iss=reliable-push`, `aud=appId`, `sub=userId`, `did`, `ver`, `iat`, `exp`). The user id always comes from the
verified credential. Every device-authenticated request verifies the token strictly and then checks the device row
(`revoked_at`, `token_version`) through a short cache (`revocation-cache-ttl`). Re-registering an existing device bumps
`token_version`, which invalidates older tokens. Details: [security.md](security.md).

## Data model

`notification`, `notification_event` and `device`, created by Flyway migrations `V1__init.sql`, `V2__event_unique_and_close_invariant.sql`
and `V3__lease_until.sql` (applied automatically at startup). The columns, constraints and indexes are listed in spec
section 3; `notification_event.notification_id` cascades on delete and `device_id` is set to null when a device is purged,
so event history outlives devices. Applied migrations are never edited.

## Time

All "minutes" of the product (notification timeout, trigger window) and the minute-based durations (webhook backoff, lease)
go through `TimeScale`. It is 60 s per minute unless `reliable-push.testing.minute-duration` is set, which is only accepted
in the `e2e` and `demo` profiles. `fallback.http-timeout` is a literal duration and is never scaled.

## Metrics, logs

Prometheus metrics (`rp_*`) and the ECS JSON log with its event catalogue are described in [operations.md](operations.md).
