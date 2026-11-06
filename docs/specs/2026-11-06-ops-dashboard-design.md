# reliable-push — Ops dashboard design spec (0.2.0)

- **Status:** design approved in conversation 2026-11-06 (sections 1-5); written spec pending maintainer review.
- **Requirements:** [`../requirements.md`](../requirements.md), new FR-16 and FR-17 (section 9 below).
- **Base spec:** [`2026-11-03-reliable-push-design.md`](2026-11-03-reliable-push-design.md). This spec adds a component
  and changes nothing that the base spec defines, except where a section below says so.
- **Release:** 0.2.0.

## 0. Purpose and decisions

One read-only dashboard for three audiences:

- **Support** (lookup): "did user X get notification Y, which device confirmed it and when, did the fallback fire and
  how did the webhook answer?"
- **Ops** (health now): delivery and read rates, fallbacks by outcome, the due backlog.
- **Product** (trends): daily volumes and rates per app over up to 400 days.

Decisions taken with the maintainer:

| # | Decision |
|---|---|
| D1 | Approach A: an admin API inside the backend plus a static page served by the backend. One artifact, no new container, no frontend toolchain, no new runtime dependency. |
| D2 | Admin API keys from configuration, stored as SHA-256 like the sender keys, each scoped to all apps or a list of apps. |
| D3 | Webhook attempts are persisted per notification; daily rollups per app survive the notification retention. |
| D4 | `userId` is visible and searchable for every admin key; the fallback payload only for keys with the `payload` permission. Searches and views are audit-logged. |
| D5 | Read-only: the dashboard never cancels a notification or re-fires a fallback. |

Out of scope: write actions, user accounts or SSO, per-user permissions beyond D2/D4, CSV export, alerting (use the
existing Prometheus metrics), a web-push or HMS channel.

## 1. Architecture

New backend module `io.reliablepush.admin`:

| Unit | Responsibility |
|---|---|
| `AdminKeys` | Parses and validates `reliable-push.admin.*`; looks up a raw key by SHA-256 in constant time (same scheme as `AppRegistry.bySenderApiKey`). |
| `AdminKeyFilter` | Authenticates `/v1/admin/**` with header `X-Admin-Key`; exposes `AdminPrincipal(keyId, apps, permissions)` as a request attribute. |
| `AdminPageFilter` | Serves `/admin/**` only when admin is enabled (404 otherwise) and sets the page's security headers (section 5). |
| `AdminController` | The read-only routes of section 4. |
| `AdminQueries` | SQL for overview, search, detail, devices (JdbcClient, no ORM). |
| `DailyStatsRepository` + `DailyRollupJob` | Rollup computation and storage (section 3.2). |
| `FallbackAttemptRepository` | Insert (worker) and read (admin) of `fallback_attempt`. |
| Static page | `backend/src/main/resources/static/admin/` (`index.html`, `admin.css`, ES modules). |

Roles: every instance that serves HTTP serves the admin API and page, as for the other APIs. Only the `worker` role
writes attempts (it is the only writer of fallback outcomes) and runs `DailyRollupJob`.

Admin is **enabled** when at least one admin key is configured. Disabled: every `/v1/admin/**` and `/admin/**` request
answers `404` (the same problem body as any unknown path), so a deployment that does not use the dashboard exposes
nothing new.

Unchanged: SDKs, sender API, device API, the webhook contract, the fallback decision and its lease fencing.

## 2. Configuration

```yaml
reliable-push:
  admin:
    rate-limit-per-minute: 600        # per admin key, per instance; at least 1
    rollup-retention-days: 400        # daily_stats rows older than this are deleted; at least 1
    rollup:
      cron: "0 7 3 * * *"             # UTC, before retention (03:17); "-" disables (tests)
    keys:
      - id: support-eu                # [a-z0-9-]{1,40}, unique; logged instead of the key
        key-sha256: <64 lowercase hex>
        apps: ["*"]                   # "*" alone = every configured app; otherwise a non-empty list of app ids
        permissions: [payload]        # optional; only "payload" exists
```

Startup fails (`IllegalStateException`, message names the key id) on: a duplicate `id`; an `id` not matching the
pattern; a `key-sha256` that is not 64 lowercase hex characters; two keys with the same hash; a hash equal to a sender
key hash of any app; an empty `apps`; `"*"` mixed with app ids; an unknown app id; an unknown permission. Defaults:
`rate-limit-per-minute` 600, `rollup-retention-days` 400, `rollup.cron` `0 7 3 * * *`, `keys` empty (admin disabled).

Generating a key and its hash is documented in `security.md` next to the sender key procedure (`openssl rand` +
`sha256sum`). Rotation: add the new key, deploy, remove the old one.

## 3. Data model (Flyway `V4__admin.sql`)

### 3.1 `fallback_attempt`

```sql
create table fallback_attempt (
    id              bigserial   primary key,
    notification_id uuid        not null references notification (id) on delete cascade,
    attempt         int         not null,
    started_at      timestamptz not null,
    duration_ms     int         not null,
    outcome         text        not null,
    http_status     int         null,
    reason          text        null,
    constraint fallback_attempt_outcome_check check (outcome in ('SUCCESS', 'RETRYABLE', 'FINAL')),
    constraint fallback_attempt_once unique (notification_id, attempt)
);
create index notification_app_created on notification (app_id, created_at);
```

- One row per webhook call the worker makes. `outcome` maps `WebhookResult`: `Success` → `SUCCESS`, `Retryable` →
  `RETRYABLE`, `Final` → `FINAL`. `http_status` is set when the endpoint answered; `reason` is the existing short log
  code (`http_503`, `timeout`, `io_error`, ...), never a response body. `attempt` is the attempt number already logged
  as `rp.attempt`.
- The row is inserted **in the same transaction** as the fenced outcome update the worker already performs (retry
  scheduled, `FALLBACK_TRIGGERED`, `FALLBACK_FAILED`). If the fence rejects the update (the worker lost its lease),
  nothing is written, so a stale holder never records an attempt. A failed insert rolls the outcome update back; the
  row is then re-claimed exactly as after any failed outcome write today.
- Outcomes that make no webhook call (an expiry before the call, `app_not_configured`) write no attempt row. An expiry
  decided after a call (`next_retry_after_trigger_window`) made a call, so it writes its attempt like any other outcome.

### 3.2 `daily_stats`

```sql
create table daily_stats (
    day                date   not null,          -- UTC day of notification.created_at
    app_id             text   not null,
    registered         int    not null,
    cancelled          int    not null,
    delivered          int    not null,          -- delivered_at is not null
    read               int    not null,          -- read_at is not null
    delivered_ios      int    not null,          -- platform of the confirming device (section text)
    delivered_android  int    not null,
    fallback_triggered int    not null,
    fallback_failed    int    not null,
    fallback_expired   int    not null,
    delivery_p50_ms    bigint null,              -- percentile of delivered_at - created_at; null without deliveries
    delivery_p95_ms    bigint null,
    computed_at        timestamptz not null,
    primary key (day, app_id)
);
```

The counts use the lifecycle's columns as they are: a `READ` without `DELIVERED` (notifications disabled, then opened)
already sets `delivered_at` (`coalesce(delivered_at, read_at)`), so it counts as delivered. The platform columns use
the device of the `DELIVERED` event, else of the `READ` event; a confirmation whose device was purged counts in
`delivered` but in neither platform column. Every configured app gets a row for every recomputed day, zeros included.

`DailyRollupJob` (worker role, `admin.rollup.cron`, UTC, runs only when admin is enabled):

1. Takes `pg_try_advisory_xact_lock(<constant>)`; another worker holding it means this run does nothing.
2. Recomputes, with one `INSERT ... ON CONFLICT (day, app_id) DO UPDATE` per day, every day `D` with
   `today - W <= D <= today - 1`, where `W = min(7, retention-days)`. Seven days because a notification can stay open
   up to 7 days (`timeoutMinutes` ≤ 10,080) and late `READ` events still change counts. A day older than `W` is never
   recomputed: retention may already have deleted part of it, and its row keeps the last complete computation.
3. Deletes `daily_stats` rows with `day < today - rollup-retention-days`.
4. Logs `rollup.completed` (`count` = days written) or WARN `rollup.failed` (`reason`).

Ordering with retention: `RetentionJob` runs after the rollup (03:17 vs 03:07 by default). When admin is enabled and
`admin.rollup.cron` is not `-`, `RetentionJob` first checks `max(computed_at)` of `daily_stats`: when it is missing or
older than 20 hours (tonight's rollup did not succeed on any worker), it skips its run with WARN
`retention.skipped reason=rollup_missing`, and the next night retries both. The check reads the database, so it holds
with several workers. With admin disabled, or the rollup schedule disabled, retention behaves as today.

### 3.3 Rates

Denominator `n = registered - cancelled`. Delivery rate `delivered / n`, read rate `read / n`, fallback rate
`fallback_triggered / n`; a rate with `n = 0` is `null` (shown as "–"). The same definitions apply to live windows and
to rollups.

## 4. Admin API (`/v1/admin`, `GET` only, contract in `openapi.yaml`)

Every response: JSON (problem+json on errors, as the other APIs), `Cache-Control: no-store`. `app` must be an app the
key may see, otherwise `404` (never `403`: no existence leak). Timestamps are ISO-8601 UTC; ranges are half-open
`[from, to)`.

| Route | Parameters | Response |
|---|---|---|
| `/me` | – | `{keyId, apps: [ids], permissions: [..]}` (`apps` expanded from `"*"`) |
| `/overview` | `app` optional (default: every visible app), `window` = `1h` \| `24h` \| `7d` (default `24h`) | per app: `{appId, window, registered, cancelled, delivered, read, fallbackTriggered, fallbackFailed, fallbackExpired, deliveryRate, readRate, fallbackRate, now: {pending, dueUnclaimed, dispatching, oldestDueAgeSeconds}}`. `now` is the current state: `pending` = `PENDING` / `DELIVERED` rows with `due_at > now`, `dueUnclaimed` = `PENDING` / `DELIVERED` rows with `due_at <= now` (due, not yet claimed), `dispatching` = `DISPATCHING` (in flight or waiting for a retry), `oldestDueAgeSeconds` = age of the oldest `dueUnclaimed` (null when none) |
| `/stats/daily` | `app` required, `from`, `to` (dates; default last 30 days; span ≤ 400 days) | `{appId, days: [{day, ...daily_stats columns..., rates, live: bool}]}`; days within `W` of section 3.2 and today are computed live from `notification` and flagged `live: true` |
| `/notifications` | `app` required; optional `userId`, `externalId`, `state`, `from`, `to`, `limit` (1-100, default 50), `cursor` | `{items: [{trackingId, externalId, userId, state, policy, createdAt, deliveredAt, readAt, closedAt, fallbackAttempts}], nextCursor}`, newest first, the cursor format of the sender list (FR-15) |
| `/notifications/{trackingId}` | – | `{notification: {...the list fields..., timeoutMinutes, dueAt}, events: [{type, occurredAt, receivedAt, late, deviceId, platform}], attempts: [{attempt, startedAt, durationMs, outcome, httpStatus, reason}], payloadBytes}`; unknown, malformed or not visible → `404` |
| `/notifications/{trackingId}/payload` | – | the stored fallback payload as JSON; `404` without the `payload` permission (same body as unknown) |
| `/devices` | `app`, `userId` required | `{items: [{deviceId, platform, createdAt, lastSeenAt, revokedAt}]}`, at most 50, newest first |

Errors: malformed parameter → `400` with the field (existing `InvalidRequestException` style); span or `limit` out of
range → `400`; unknown route → `404`; rate limit → `429` + `Retry-After` (bucket `admin:<keyId>`, existing
`RateLimiter`); database unavailable → `503`.

Audit (Log4j 2 ECS, logger `io.reliablepush.event`, INFO): `admin.search` (`rp.admin_key`, `rp.app_id`, `filters` =
names of the filters used, never their values), `admin.notification_viewed` and `admin.payload_viewed`
(`rp.admin_key`, `rp.app_id`, `rp.tracking_id`, `rp.user_hash`). Authentication failures reuse `auth.failed` with
`reason` = `missing_admin_key` / `unknown_admin_key`. The key, `userId` and payload never appear in a log line.

## 5. Page (`/admin/`)

Static, served by the backend; plain HTML, CSS and JavaScript ES modules; no third-party code, no CDN, no build.
Hash routes: `#/overview`, `#/trends`, `#/search`, `#/n/<trackingId>`, `#/devices/<app>/<userId>` (path segments
percent-encoded). Light and dark themes; usable from 360 px wide.

1. **Sign-in:** a key field; `GET /me` validates it; the key is kept in `sessionStorage` only, sent as `X-Admin-Key`,
   never in a URL. "Sign out" clears it. A `401` from any call returns to sign-in.
2. **Overview:** app selector (all visible apps or one) and window (1 h, 24 h, 7 d); tiles for registered, delivery
   rate, read rate, fallbacks triggered / failed / expired; a "now" block with the backlog, amber when
   `oldestDueAgeSeconds > 60`. Refreshes every 30 s; a pause toggle stops it.
3. **Trends:** hand-drawn inline SVG charts of daily volumes and of the three rates, ranges 30 / 90 / 400 days, and the
   same numbers in a table; `live` days are marked as still changing.
4. **Search:** app plus any of `userId`, `externalId`, `trackingId` (opens the detail directly), state and time range;
   a results table with coloured state chips and "next page".
5. **Notification detail:** a vertical timeline: created → due → each `DELIVERED` / `READ` (device, platform, late) →
   each webhook attempt (status, reason, duration) → closed with the final state. Payload: a "Show payload" button
   (keys with `payload`) that calls the payload route on demand, otherwise "hidden (N bytes)". A link to the user's
   devices.

Security headers on `/admin/**` (set by `AdminPageFilter`): `Content-Security-Policy: default-src 'self';
frame-ancestors 'none'; base-uri 'none'; form-action 'none'`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
`X-Content-Type-Options: nosniff`. Every server value is inserted with `textContent` (never `innerHTML`), so a crafted
`userId` or payload cannot run script. A `429` waits for `Retry-After`; a network error or `503` shows a banner and
retries.

## 6. Deployment

- Helm: `values.yaml` gains `config.admin` rendered into the application config (keys are hashes, not secrets);
  default empty (disabled). No new pod, port or Service. `deployment.md` explains restricting `/admin/` and
  `/v1/admin/` to an internal network at the Ingress / Route when wanted.
- Harness compose: one admin key with `apps: ["*"]` and `payload`, its raw value generated per run next to the other
  per-run secrets (`harness/build/e2e/secrets.env`), never committed.

## 7. Testing

- **Unit:** `AdminKeys` (lookup, scope, every startup failure of section 2), rate computation (`n = 0`), rollup day
  window (`retention-days` 1, 7, 30), percentile helper, cursor reuse.
- **Integration (Testcontainers PostgreSQL):** each route, `404` outside the scope and without `payload`, `400`s, `429`,
  audit events captured from the log, `no-store`; worker writes one attempt per call with the right outcome, a
  fenced-out stale holder writes none; rollup upsert and its window, the advisory lock (second run writes nothing),
  `retention.skipped` after `rollup.failed`; the OpenAPI contract (`OpenApiAssert`) for every admin response.
- **Coverage:** the backend's 80 % line gate stays.
- **E2E (local):** new scenario `admin-dashboard`: registers notifications, has the simulated device confirm some,
  lets others fall back (success and failure receiver), then checks `/overview`, `/notifications` search and the
  detail timeline including attempts; `./scripts/e2e.sh` expects `E2E: 18/18 passed`. A Playwright smoke test of
  `/admin/` (sign-in, overview, search, detail) joins the console smoke run.

## 8. Documentation (docs-sync rule)

New `docs/admin.md` (using the dashboard, keys, permissions, audit). Updated: `api.md` (admin API),
`operations.md` (configuration keys, `DailyRollupJob`, new log events, retention ordering), `security.md` (admin keys,
generation and rotation, audit, page headers), `architecture.md` (module), `deployment.md` (Helm values, network
restriction), `testing.md` (scenario and smoke), `overview.md` (a new diagram section), the at-a-glance picture (a
"Dashboard" element on the reliable-push backend; SVG and PNG), `presentation.html` (a dashboard slide, status
slide), `README.md`, `requirements.md`, `CHANGELOG.md`, `TODO.md`, `STATUS.md`.

## 9. Requirements added to `requirements.md`

- **FR-16** A read-only web dashboard served by the backend shows, per app, delivery, read and fallback rates for the
  last hour, day or week; the current due backlog; daily trends kept for up to 400 days; a search by user, external id,
  tracking id, state and time; and per notification a timeline of events and webhook attempts.
- **FR-17** The dashboard and its API are protected by admin API keys from configuration (stored hashed), each scoped
  to all apps or a list of apps; the fallback payload is shown only to keys with the `payload` permission; every
  search and view is audit-logged without personal data. With no admin key configured, neither the page nor the API
  exists.
