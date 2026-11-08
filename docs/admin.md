# Ops dashboard

A web page and API served by the backend for support, ops and product, read-only except the fallback resume. It answers:
did user X get notification Y, which device confirmed it, did the fallback fire and how did the webhook answer; how
healthy are delivery and read rates now; how do volumes and rates trend over months; is an app's fallback circuit open.

Requirements: FR-16, FR-17, FR-19 ([`requirements.md`](requirements.md)). Design: [`specs/2026-11-06-ops-dashboard-design.md`](specs/2026-11-06-ops-dashboard-design.md),
circuit view and resume: [`specs/2026-11-07-fallback-circuit-breaker-design.md`](specs/2026-11-07-fallback-circuit-breaker-design.md).
API contract: [`api.md`](api.md#admin-api) and `openapi.yaml`.

## Turning it on

The dashboard exists only when at least one admin key is configured (`reliable-push.admin.keys`). With no key,
`/v1/admin/**` and `/admin/**` answer exactly like any unknown path (`404`): there is no admin surface at all.

1. Generate a key and its hash ([`security.md`](security.md#admin-keys)).
2. Put the hash in the configuration (`reliable-push.admin.keys[]`, or Helm `config.admin`, see
   [`deployment.md`](deployment.md#ops-dashboard)).
3. Deploy, open `https://<your-host>/admin/` and enter the key.

Every instance that serves HTTP serves the page and the API. Only the `worker` role runs the nightly rollup.
Configuration keys: [`operations.md`](operations.md#configuring-the-service).

## Keys and permissions

Each key has an `id` (shown in the page header and in audit logs, never the key itself), a scope and permissions.

| Setting | Meaning |
|---|---|
| `apps: ["*"]` | The key sees every configured app |
| `apps: [a, b]` | The key sees only these apps. Any other app, and any notification of it, answers `404` (never `403`) |
| `permissions: [payload]` | The key may read the stored fallback payload. Without it, the payload route answers `404` and the page shows "hidden (N bytes)" |
| `permissions: [fallback-control]` | The key may resume the fallbacks of an app whose circuit is open (`POST /v1/admin/fallback-circuits/{appId}:resume`). Without it the route answers `403`. Reading the circuits and their history needs no permission |

`userId` is visible and searchable for every admin key. The fallback payload (SMS text, e-mail body) is the only
data behind a read permission; `fallback-control` is the only permission that changes something.

## Signing in

Open `/admin/`, paste the admin key, press Sign in. The page calls `GET /v1/admin/me` to check it. The key is kept in
`sessionStorage` of that browser tab only, sent as header `X-Admin-Key`, and never put in a URL. "Sign out" clears it.
A `401` from any call returns to the sign-in form. Closing the tab forgets the key.

## Views

| View | Route | What it shows |
|---|---|---|
| Overview | `#/overview` | Per app (or all visible apps) and window (1 h, 24 h, 7 d): registered, delivery rate, read rate, fallbacks triggered, failed and expired. A fallback circuit badge per app (`CLOSED`, `OPEN since <time> — <n> suppressed`, `FORCED until <time>`) linking to the circuit view, a banner "Fallbacks suspended by configuration" when the config suspends all fallbacks and a note "Circuit breaker disabled" when the breaker is off (the badges are left out, without delaying the tiles, when the circuit route fails). A "Backlog now" block: pending, due and not yet claimed, dispatching, age of the oldest due notification (amber above 60 s). Refreshes every 30 s; "Pause refresh" stops it |
| Trends | `#/trends` | Daily volumes and the three rates as inline SVG charts, for 30, 90 or 400 days, with the same numbers in a table. Days still changing are marked `live` |
| Search | `#/search` | One app plus any of user id, external id, tracking id (opens the detail directly), state and a created-time range. Newest first, 50 per page, "next page" |
| Notification | `#/n/<trackingId>` | Timeline: created, due, each `DELIVERED` and `READ` event (device, platform, late or not), each webhook attempt (outcome, HTTP status, reason, duration), closed with the final state. "Show payload" for keys with `payload`. A link to the user's devices |
| Fallback circuit | `#/circuit/<app>` | Reached from the badge on the overview. The app's circuit state and its history (when, transition `OPENED` / `CLOSED` / `FORCED` / `FORCE_ENDED`, reason, due count, ratio, suppressed count, admin key), newest first, "Next page" for more. For keys with `fallback-control` and an `OPEN` circuit, a "Resume fallbacks" button asks for confirmation (`window.confirm`) and then shows the `FORCED` state; a `409` (suspended or disabled) or `503` (a detector run holds the lock) shows the server's message, and after a `409` or `404` the circuit is read again. What to check before resuming: [runbook](operations.md#runbook-circuit-open) |
| Devices | `#/devices/<app>/<userId>` | The user's devices, revoked ones included, newest first, at most 50 |

Rates are `count / (registered - cancelled)`; with a zero denominator the page shows "–".

Numbers come from two places. The live windows (1 h, 24 h, 7 d) and the most recent days are computed from the
`notification` table. Older days come from `daily_stats`, written every night by `DailyRollupJob` and kept for
`admin.rollup-retention-days` (400 by default), long after the notifications themselves are purged.

## Audit

Every search and view is logged (logger `io.reliablepush.event`, INFO) without personal data:

| Event | Fields |
|---|---|
| `admin.search` | `rp.admin_key`, `rp.app_id`, `filters` (names of the filters used, never their values) |
| `admin.notification_viewed` | `rp.admin_key`, `rp.app_id`, `rp.tracking_id`, `rp.user_hash` |
| `admin.payload_viewed` | same |
| `admin.devices_viewed` | `rp.admin_key`, `rp.app_id`, `rp.user_hash` (devices of a user; the user id itself is never logged) |
| `admin.fallback_resumed` | `rp.admin_key`, `rp.app_id` (an operator resumed the fallbacks of an app) |

A failed sign-in logs `auth.failed` with `reason` `missing_admin_key` or `unknown_admin_key`; a key over its limit logs
`rate_limit.exceeded` with `rate_limit.scope=admin`. The key, the user id and the payload never appear in a log line.
Field details: [`operations.md`](operations.md#event-catalogue).

## What it does not do

- It is **read-only**, except "Resume fallbacks" for keys with `fallback-control`. It cannot cancel a notification, re-fire a fallback or change a configuration.
- There are no user accounts, SSO or per-user permissions: the admin key is the identity. Rotate it as in
  [`security.md`](security.md#admin-keys).
- No CSV export and no alerting; use the Prometheus metrics for alerts.

## Known limits

- Trends for days older than the 7-day live window come from the stored nightly rollups. A day without a stored
  rollup (before the first run, or while the rollup failed) is missing from the chart.
- With `retention-days` of 7 or less, the oldest live day can undercount: rows are already purged. The rollup window
  is `min(7, retention-days)` days, and a day older than that keeps its last complete computation.
- If tonight's rollup did not run, `RetentionJob` skips its purge (`retention.skipped`) until it succeeds; a rollup still
  running when retention starts is waited for up to 30 minutes. This guard applies only while the rollup schedule is on (`admin.rollup.cron` is not `-`).
- The page needs a modern browser with ES modules (current Chrome, Firefox, Safari, Edge). It loads no third-party
  code and no CDN.
- Rate limit: `admin.rate-limit-per-minute` (600) per key and instance; the page waits for `Retry-After` on `429`.

## Page security

`/admin/**` answers with `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'; base-uri 'none';
form-action 'none'`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and `X-Content-Type-Options: nosniff`, plus
`Cache-Control: no-cache` (the browser revalidates the files on every load, so after an upgrade a tab never mixes old and new
modules).
Every server value is inserted with `textContent`, so a crafted `userId` or payload cannot run script. API answers
carry `Cache-Control: no-store`. To limit who can reach the page at all, restrict `/admin/` and `/v1/admin/` to an
internal network at the Ingress or Route ([`deployment.md`](deployment.md#ops-dashboard)).

## Trying it

`./scripts/demo.sh` starts the demo stack with an admin key; see [`getting-started.md`](getting-started.md#1-ten-minute-tour-the-demo-console).
