# reliable-push — Fallback circuit breaker design spec (0.2.0)

- **Status:** design approved in conversation 2026-11-07; written spec pending maintainer review.
- **Requirements:** [`../requirements.md`](../requirements.md), new FR-18 and FR-19, and a change to FR-16/FR-17
  (section 9 below).
- **Base specs:** [`2026-11-03-reliable-push-design.md`](2026-11-03-reliable-push-design.md) and
  [`2026-11-06-ops-dashboard-design.md`](2026-11-06-ops-dashboard-design.md). This spec changes nothing they define,
  except where a section below says so.
- **Release:** 0.2.0 (not yet released).

## 0. Purpose and decisions

reliable-push serves millions of notifications, and every fallback costs money (SMS). If `DELIVERED` events stop
reaching the backend for a systemic reason (reliable-push API or ingress down, an SDK bug, a bad app release), every
notification of that period comes due unmet and triggers a fallback the user did not need. A missed fallback is
preferred to a storm of needless ones.

Decisions taken with the maintainer:

| # | Decision |
|---|---|
| D1 | A circuit breaker on fallback triggering, **per app**. It opens by itself on a massive lack of delivery tracking and closes by itself when `DELIVERED` events arrive again. |
| D2 | While a circuit is open, no webhook call is made for that app. Notifications that come due are closed without a fallback and **never resumed**: there is no queue to restore. |
| D3 | Two global configuration entries: one enables or disables the circuit breaker, one suspends fallback triggering for every app regardless of anything else. |
| D4 | The admin console shows each app's circuit and its history. An operator with the new `fallback-control` permission can force fallbacks back on; the forced state holds for 60 minutes or until `DELIVERED` events come back, then automatic detection resumes. This is the console's only write action (it changes the ops-dashboard decision D5 "read-only"). |
| D5 | Every open and close is stored in the database (stop-and-go history), logged and exposed as a metric. |
| D6 | The webhook call itself is unchanged (retries, `Idempotency-Key`, trigger window). |

Out of scope: the iOS extension sending `DELIVERED` without waiting for the result (a separate spec); detection based
on `READ` (a broken `trackOpen` does not trip the circuit); per-app values of the two configuration entries; alerting
beyond logs and metrics.

## 1. Architecture

```
worker role: FallbackScheduler.tick()
  1. CircuitBreaker.evaluate()        one worker at a time (advisory lock); reads the notifications coming due and
                                      the delivery ratio of their cohort (notification.delivered_at);
                                      writes fallback_circuit / fallback_circuit_event on a transition
  2. Suppressor                       apps with an OPEN circuit, or all apps when suspended: bulk-close their due
                                      rows as FALLBACK_EXPIRED (no claim, no webhook call)
  3. claim and dispatch               the other apps, as today
```

New code lives in `io.reliablepush.fallback.circuit` (circuit state, detection, suppression). The API role is
unchanged: detection reads `delivered_at`, which `POST /v1/events` already writes. The admin module gains three routes
and one permission.

## 2. Configuration

```yaml
reliable-push:
  fallback:
    suspended: false                  # true: no fallback for any app, always; wins over everything
    circuit-breaker:
      enabled: true                   # false: no detection, every circuit reads as CLOSED (today's behaviour)
      window: 5m                      # detection window
      min-due: 50                     # trip only when at least this many fallbacks came due in the window
      max-delivered-ratio: 0.10       # trip when less than this share of the cohort was delivered
      close-min-delivered: 10         # close when this many notifications created after the open were delivered
      min-open: 1m                    # an open circuit stays open at least this long
      force-hold: 60m                 # how long a forced resume keeps detection off
```

`suspended` and `circuit-breaker.enabled` are the two entries of D3; the others are optional tuning with the defaults
shown (for apps with little traffic). Startup fails (`IllegalStateException`) on `window`, `min-open` or `force-hold`
not positive, `min-due` or `close-min-delivered` below 1, `max-delivered-ratio` outside `(0, 1]`. Bare numbers mean
minutes for the durations, as for the other worker settings. Changes take effect on restart (Helm: `config.fallback`).

## 3. Data model (Flyway `V6__fallback_circuit.sql`)

```sql
create table fallback_circuit (
    app_id       text        primary key,
    state        text        not null check (state in ('CLOSED', 'OPEN', 'FORCED')),
    since        timestamptz not null,
    forced_until timestamptz null,
    forced_by    text        null,       -- admin key id, never the key
    suppressed   bigint      not null default 0   -- fallbacks closed without a call since `since`
);

create table fallback_circuit_event (
    id            bigserial   primary key,
    app_id        text        not null,
    at            timestamptz not null,
    transition    text        not null check (transition in ('OPENED', 'CLOSED', 'FORCED', 'FORCE_ENDED')),
    reason        text        not null,  -- tracking_missing, delivered_resumed, forced, force_expired
    due           bigint      null,      -- what the detector saw at that moment
    ratio         numeric     null,      -- delivered share of the cohort
    suppressed    bigint      null,      -- on CLOSED / FORCED: how many fallbacks the open period suppressed
    admin_key_id  text        null
);
create index fallback_circuit_event_app_at on fallback_circuit_event (app_id, at desc);
```

- An app with no `fallback_circuit` row is `CLOSED`.
- `fallback_circuit_event` is kept like `daily_stats` (`admin.rollup-retention-days`, 400 by default; deleted by the
  detector once a day).

## 4. Detection and transitions

`CircuitBreaker.evaluate()` runs at the start of every `FallbackScheduler.tick()` (default every 5 s), inside a
transaction holding `pg_try_advisory_xact_lock(4242002)`: the worker that gets the lock evaluates, the others skip it
and read the stored states. With `circuit-breaker.enabled: false` it does nothing and every app is `CLOSED`.

For each configured app, with `now` the database clock:

- **due set**: the app's fallbacks of the window, that is rows with `state in ('PENDING', 'DELIVERED', 'DISPATCHING')`
  and `due_at in (now - window, now]` (the backlog about to fire plus calls in progress; partial index
  `notification_due`) and rows closed as `FALLBACK_TRIGGERED` or `FALLBACK_FAILED` with `closed_at > now - window`
  (index `notification_closed`). `due` is its size, counted up to `min-due` only (`limit`), so a quiet app costs two
  tiny index reads.
- **cohort**: only when `due >= min-due`. The notifications of the app created in the creation range of the due rows
  read (`min(created_at)` to `max(created_at)` of those `min-due` rows; index `notification_app_created`), newest
  first, at most 1,000 rows.
  `ratio` = share of the cohort with `delivered_at` set. The cohort is the same batch of sends as the fallbacks
  coming due: a normal app shows most of it delivered (only the offline users fall back), a tracking outage shows
  almost none. A campaign followed by silence does not trip it, and an outage during the sends is seen when their
  fallbacks come due, even if tracking works again by then.
- **resumed**: of the at most 1,000 newest notifications of the app created after the circuit's `since`, at least
  `close-min-delivered` have `delivered_at` set.

| From | Condition | To | Event, log |
|---|---|---|---|
| CLOSED | `due >= min-due` and `ratio < max-delivered-ratio` | OPEN | `OPENED`/`tracking_missing`; WARN `fallback.circuit_opened` (`rp.app_id`, `due`, `ratio`) |
| OPEN | `now - since >= min-open` and resumed | CLOSED | `CLOSED`/`delivered_resumed`; INFO `fallback.circuit_closed` (`reason`, `suppressed`) |
| OPEN or CLOSED | operator resume (section 6) | FORCED, `forced_until = now + force-hold` | `FORCED`/`forced` with `admin_key_id`; WARN `fallback.circuit_forced` |
| FORCED | `now >= forced_until`, or resumed (counted from the forced `since`) | CLOSED | `FORCE_ENDED`/`force_expired` or `delivered_resumed`; INFO `fallback.circuit_closed` |

Because the due set counts the backlog before it is claimed, a storm is stopped at the first tick: the rows that would
fire are still unclaimed when the circuit opens. A storm that builds slowly (a few rows per tick) is caught once the
window holds `min-due` of them. While `FORCED`, detection is off for that app. `NOT_READ` notifications whose device
confirmed delivery count as delivered in the cohort, so users who do not open a notification never trip the circuit.

## 5. Suppression

After `evaluate()`, in the same tick and before any claim:

- When `fallback.suspended` is `true`: every app is suppressed, whatever its circuit state (also `FORCED`).
- Otherwise each app whose circuit is `OPEN` is suppressed.

Suppressing an app closes its claimable rows (the claim query's own condition, restricted to the app:
`state in ('PENDING','DELIVERED') and due_at <= now` or `state = 'DISPATCHING' and next_attempt_at <= now`) as
`FALLBACK_EXPIRED` (`closed_at = now`, `due_at`, `next_attempt_at`, `lease_until` cleared) in chunks of 1,000 rows,
each its own transaction, without calling the webhook. Rows a worker holds under a live lease are left to that worker.
Per tick and app: one WARN `fallback.suppressed` (`rp.app_id`, `count`, `reason` = `circuit_open` or
`fallback_suspended`), never one line per notification; `fallback_circuit.suppressed` grows by the count; the metric
`rp_fallback_total{outcome="expired"}` and the new `rp_fallback_suppressed_total{app,reason}` grow. The claim that
follows skips suppressed apps. Suppressed notifications are never re-opened: events that arrive later are stored as
`late`, as today (FR-11).

## 6. Admin console and API

- New admin key permission `fallback-control` (next to `payload`; unknown permissions still fail startup).
- `GET /v1/admin/fallback-circuits` → `{items: [{appId, state, since, forcedUntil, forcedBy, suppressed}]}` for the apps
  in the key's scope (absent rows as `CLOSED`), plus `suspended` (the configuration entry) and `enabled`.
- `GET /v1/admin/fallback-circuits/{appId}/events?limit=&cursor=` → the history, newest first, `{items, nextCursor}`.
- `POST /v1/admin/fallback-circuits/{appId}:resume` → `200` with the new circuit; needs `fallback-control` (else
  `403`); an app outside the scope → `404`; with `suspended: true` or `enabled: false` → `409` (nothing to resume).
  Audit event `admin.fallback_resumed` (`rp.app_id`, admin key id). Same `X-Admin-Key` authentication and rate limit as
  the other admin routes; no cookie, so no CSRF surface.
- Page: the overview shows a circuit badge per app (CLOSED / OPEN since ... with the suppressed count / FORCED until
  ...) and a banner "Fallbacks suspended by configuration" when `suspended` is true; a "Fallback circuit" view lists the
  history; the "Resume fallbacks" button appears only for keys with `fallback-control` and asks for confirmation.

## 7. Testing

- **Unit:** the transition table of section 4 as a pure function (every row, boundaries `min-due - 1`, ratio exactly at
  the limit, `min-open` not elapsed, `forced_until` reached), configuration validation.
- **Integration (Testcontainers PostgreSQL):** the due set and cohort queries (a campaign followed by silence with 30 %
  offline users does not trip; a cohort with no delivery trips even though new sends are delivered); detection opens the
  circuit before any claim of a storm backlog (no webhook call, rows `FALLBACK_EXPIRED`, one `fallback.suppressed`
  line); close on resumed `DELIVERED`; `suspended: true` suppresses also a `FORCED` app; `enabled: false` never opens;
  the advisory lock (two workers, one transition); a row under a live lease is not suppressed; admin routes (`403`
  without the permission, `404` outside the scope, `409`, audit line, OpenAPI contract).
- **E2E (local):** new scenario `fallback-circuit`: registers a burst of notifications whose devices never confirm,
  checks that the circuit opens and the receiver gets no call, then lets devices confirm new notifications and checks
  that the circuit closes and later fallbacks fire again. `./scripts/e2e.sh` expects `E2E: 19/19 passed`.
- **Coverage:** the backend's 80 % line gate stays.

## 8. Documentation (docs-sync rule)

`operations.md` (configuration keys, detection, log events, metrics, runbook "circuit open"), `admin.md` (circuit
view, resume, permission), `api.md` and `openapi.yaml` (admin routes), `security.md` (`fallback-control`),
`architecture.md` (module, `V6`), `deployment.md` (Helm `config.fallback`), `integration-guide.md` (what senders see:
`FALLBACK_EXPIRED` during an open circuit), `testing.md` (scenario), `overview.md` (fallback diagram: the breaker before
the claim), the at-a-glance picture (SVG and PNG), `presentation.html` (fallback slide and status), `README.md`,
`requirements.md`, `CHANGELOG.md`, `TODO.md`, `STATUS.md`.

## 9. Requirements

Added to `requirements.md`:

- **FR-18** The backend stops triggering fallbacks for an app when, within a configurable window, many notifications
  come due unmet while almost no `DELIVERED` event arrives for that app (circuit open). It resumes by itself when
  `DELIVERED` events arrive again. Notifications that come due while the circuit is open end without a fallback and
  are never resumed. Every open and close is logged, counted and stored with its counters.
- **FR-19** Configuration can disable the circuit breaker, and can suspend fallback triggering for every app regardless
  of the circuit. An operator with the admin permission `fallback-control` can force an open circuit closed from the
  admin console for a configurable time; the action is audit-logged.

Changed: **FR-16** "A read-only web dashboard" becomes "A web dashboard, read-only except the fallback resume of FR-19".
**FR-17** lists the `fallback-control` permission next to `payload`.
