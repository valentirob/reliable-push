# Operations

Running the backend: configuration, profiles, health and metrics, logs, retention, scaling and the container image.
Everything on this page exists in the code; configuration keys are validated at startup, so a wrong value fails the start
with a message naming the key instead of failing the first request.

## Roles and profiles

| Profile | Effect |
|---|---|
| `api` | Marks an API-only instance: activate it **without** `worker` to run no background jobs. The HTTP endpoints themselves are not gated by it: every instance serves them |
| `worker` | Enables the fallback scheduler (`FallbackScheduler`), the webhook client, `RetentionJob` and `DueBacklogJob` (`rp_due_backlog`) |
| `json` | ECS JSON log lines on stdout (default whenever `local` is not active) |
| `local` | Human-readable log pattern for development (`%d{ISO8601} %-5level [%t] %c{1.} [trace=...] - %m`) |
| `e2e`, `demo` | Allow the fast clock (`reliable-push.testing.minute-duration`), used by the local harness only. Also append the same ECS JSON log lines to the file `RP_LOG_FILE` (default `/tmp/rp-logs/backend.json`, append, no rotation; stdout unchanged), which the harness tails from a shared volume |

With no profile set the default is `api,worker` (`spring.profiles.default`). That default applies **only when no profile is
active**: as soon as you set `SPRING_PROFILES_ACTIVE`, list the roles explicitly, for example `api,worker,local` (just
`local` would start an instance without the worker). To split roles use `SPRING_PROFILES_ACTIVE=api` for the API replicas
and `SPRING_PROFILES_ACTIVE=worker` for the worker replicas. Both need the database and the same `reliable-push.*`
configuration (a worker needs the apps' fallback settings; an instance that serves the API needs the identity settings and
the signing keys). A `worker`-only instance still answers HTTP, so keep it out of the ingress routing (probes and metrics
only). Do not set `e2e`/`demo` in production.

## Configuring the service

Standard Spring Boot configuration. Two ways are practical:

1. A YAML file, for example mounted at `/config/application.yml` and loaded with
   `SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/config/`. A starting point is the example in the
   [README quick start](../README.md#quick-start) and the sample below.
2. Environment variables. Spring maps a property to a variable by removing dashes, replacing dots with underscores and
   upper-casing, with `_<index>_` for list entries: `reliable-push.apps.shop.fallback.url` is
   `RELIABLEPUSH_APPS_SHOP_FALLBACK_URL`, and `reliable-push.device-token.signing-keys[0].kid` is
   `RELIABLEPUSH_DEVICETOKEN_SIGNINGKEYS_0_KID`. Use the dash-less form: variables starting with `RELIABLE_PUSH_` bind
   nothing under `reliable-push.*`. With no app configured the start fails with `no apps configured under
   reliable-push.apps (environment variables must use the RELIABLEPUSH_APPS_<ID>_... form)`; other keys spelled that way
   (for example the signing keys) are silently ignored, so check them too. The map key (the app id) comes out lower-cased, so app ids containing `-`, `_` or upper-case letters cannot
   be expressed through environment variables; use a YAML file for those.

**Secrets and keys are never configured inline.** Every `*-ref` key takes `env:NAME`: the value is read verbatim from the
environment variable `NAME` (no `${...}` indirection, so a secret may contain `${`). A value that is not of the form
`env:NAME`, or a variable that is unset or blank, fails the startup; error messages never print the value.

Database connection (Spring Boot): `SPRING_DATASOURCE_URL` (`jdbc:postgresql://host:5432/db`), `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`. Flyway migrations run automatically at startup.

### Configuration reference (`reliable-push.*`)

Durations take a unit (`500ms`, `30s`, `2m`, `1h`, `90d`). A bare number is read in the key's own unit where the table
says so (minutes for `worker.lease` and `fallback.backoff`, days for `device-token-validity`), otherwise as milliseconds
(Spring's default), so always write the unit for `poll-interval`, `revocation-cache-ttl` and `http-timeout`.

| Key | Default | Validation / notes |
|---|---|---|
| `device-token.signing-keys[]` | none | List of `{kid, private-key-pem-ref, public-key-pem-ref}`. `kid`s must be unique; both refs are `env:NAME`; private key PKCS#8 PEM (`BEGIN PRIVATE KEY`), public key SPKI PEM (`BEGIN PUBLIC KEY`), both P-256 and matching. With no keys the application starts but cannot issue or verify device tokens: configure at least one on every API instance |
| `device-token.current-kid` | none | Required when keys exist; must be one of the `kid`s. Signs new tokens; every configured kid still verifies |
| `device-token.revocation-cache-ttl` | `60s` | How long a "device is active" answer is cached per instance (revocation latency on other instances) |
| `worker.poll-interval` | `5s` | Time between fallback claim ticks |
| `worker.batch-size` | `100` | 1-10000; at most this many rows per claim. A tick keeps claiming while claims come back full, for up to half the lease |
| `worker.max-concurrency` | `batch-size` | 1-10000; webhook calls in flight per worker instance (one virtual thread each). A row is claimed only when a slot is free |
| `worker.lease` | `2m` | A product duration (scaled by the fast clock); a bare number means minutes. Must be longer than every app's `fallback.http-timeout`; otherwise startup fails (a warning only with the fast clock) |
| `worker.shutdown-timeout` | largest app `fallback.http-timeout` + `5s` | Positive; how long a stopping worker (`SIGTERM`) waits for its webhook calls in flight. Keep `terminationGracePeriodSeconds` above it (see "Graceful stop" below) |
| `worker.backlog-refresh-interval` | `30s` | Optional; how often `rp_due_backlog` is recomputed |
| `retention-days` | `30` | At least 1. Closed notifications older than this are deleted daily |
| `retention.cron` | `0 17 3 * * *` | Spring cron expression (six fields, always UTC) of the daily retention run (worker role); `-` disables the schedule |
| `admin.keys[]` | none (admin disabled) | Admin API keys of the ops dashboard: `{id, key-sha256, apps, permissions}`; see `security.md`. With no key, `/v1/admin/**` and `/admin/**` answer 404 |
| `admin.rate-limit-per-minute` | `600` | At least 1. Admin requests per minute per admin key and instance |
| `admin.rollup-retention-days` | `400` | At least 1. `daily_stats` rows older than this are deleted by the rollup |
| `admin.rollup.cron` | `0 7 3 * * *` | Spring cron expression (UTC) of `DailyRollupJob` (worker role, runs only when admin is enabled); `-` disables the schedule and the retention guard |
| `testing.minute-duration` | unset (1 minute = 60 s) | Fast clock, e.g. `1s`. Accepted only with the `e2e` or `demo` profile, must be positive |

Per app, under `apps.<appId>` (the app id matches `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`; at least one app is required, a
configuration without any app fails the start):

| Key | Default | Validation / notes |
|---|---|---|
| `sender-api-key-sha256` | required | 64 lowercase hex characters (SHA-256 of the sender key); unique across apps |
| `identity.type` | required | `oidc` or `signed-grant` |
| `identity.issuer` | required | For `oidc`: the exact `iss` of ID tokens; https required, http only for host `localhost`/`127.0.0.1`. Required by validation for `signed-grant` too |
| `identity.audience` | required | `oidc`: must be contained in the token's `aud`. (`signed-grant` requires `aud` = the app id) |
| `identity.user-claim` | `sub` | Claim that becomes the user id (non-blank string, at most 256 characters) |
| `identity.jwks-uri` | discovery | Optional; otherwise `<issuer>/.well-known/openid-configuration` is used. https (http only for a local issuer) |
| `identity.grant-secret-ref` | none | Required for `signed-grant` (`env:NAME`, at least 32 bytes), rejected for other types |
| `device-token-validity` | `90d` | Positive duration (a bare number means days); lifetime of issued device tokens |
| `default-policy` | `NOT_DELIVERED` | `NOT_DELIVERED` or `NOT_READ`; used when a registration omits `policy` |
| `default-timeout-minutes` | `10` | At least 1; used when a registration omits `timeoutMinutes` |
| `rate-limits.device-per-minute` | `60` | At least 1; per device, and per user for registration |
| `rate-limits.sender-per-minute` | `6000` | At least 1; per sender app |
| `fallback.url` | required | Absolute http(s) URL; use https in production |
| `fallback.hmac-secret-ref` | required | `env:NAME` of the webhook HMAC secret |
| `fallback.max-attempts` | `5` | 1-20 |
| `fallback.backoff` | `1m, 2m, 4m, 8m` | Positive durations between attempts; a bare number means minutes (`1,2,4,8` = the default), as a comma-separated value or a YAML list. When shorter than `max-attempts - 1` the last value repeats |
| `fallback.trigger-window-minutes` | `20` | At least 1, and at least the cumulative backoff of `max-attempts` attempts (startup fails otherwise). Leave margin: a retry whose time falls after `due_at + window` expires instead of firing |
| `fallback.http-timeout` | `10s` | Positive; bounds the whole webhook exchange. A literal duration, never scaled by the fast clock |

Sample (see [security.md](security.md) for generating keys and hashes):

```yaml
reliable-push:
  device-token:
    signing-keys:
      - kid: k1
        private-key-pem-ref: env:RP_DEVICE_KEY_K1
        public-key-pem-ref: env:RP_DEVICE_PUB_K1
    current-kid: k1
  worker: { poll-interval: 5s, batch-size: 100, lease: 2m }
  retention-days: 30
  apps:
    shop:
      sender-api-key-sha256: "<64 hex characters>"
      identity: { type: oidc, issuer: "https://idp.example.com", audience: shop-mobile, user-claim: sub }
      device-token-validity: 90d
      default-policy: NOT_DELIVERED
      default-timeout-minutes: 10
      rate-limits: { device-per-minute: 60, sender-per-minute: 6000 }
      fallback:
        url: https://fallback.example.com/hook
        hmac-secret-ref: env:RP_SHOP_HOOK_SECRET
        max-attempts: 5
        backoff: [1m, 2m, 4m, 8m]
        trigger-window-minutes: 20
        http-timeout: 10s
```

Other useful standard settings: `SERVER_PORT` (default 8080), `LOGGING_LEVEL_<LOGGER>` (for example
`LOGGING_LEVEL_IO_RELIABLEPUSH=DEBUG`; DEBUG output never contains secrets but is noisy),
`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` (default 10) and `SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT` (milliseconds;
the application sets 5000 instead of Hikari's 30000, see "Database pool" below).

## Health and metrics

- Kubernetes probes: `GET /actuator/health/liveness` and `GET /actuator/health/readiness` (probes enabled). Only
  `health` and `prometheus` are exposed under `/actuator`. By default they share the application port: do not route
  `/actuator/**` through a public ingress. In Kubernetes set `MANAGEMENT_SERVER_PORT=8081` (the Helm chart does):
  `/actuator/**` is then served on 8081 only (the application port answers 404), probes and Prometheus scrapes target
  8081, and probe calls no longer produce `api.request` log lines. `/actuator/**` is unauthenticated and never reaches
  the rate limiter on either port.
- Prometheus: `GET /actuator/prometheus` (standard JVM and HTTP server metrics plus the application metrics below).

| Metric | Type | Meaning |
|---|---|---|
| `rp_notifications_registered_total` | counter | Notifications inserted (replays of an `externalId` are not counted) |
| `rp_events_total{type,late}` | counter | Event rows stored; `type` = `DELIVERED`/`READ`, `late` = `true` when the transition no longer applied |
| `rp_fallback_total{outcome}` | counter | Fallback outcomes persisted by the worker: `triggered`, `failed`, `expired` |
| `rp_webhook_latency_seconds` | timer | Duration of every webhook call (`_count`, `_sum`, `_max`) |
| `rp_due_backlog` | gauge | Rows the worker could claim now, refreshed every 30 s. **Worker role only**: an `api`-only replica does not expose it |

Tags are fixed enumerations (never app, user or tracking ids); all series exist at 0 from startup. Useful alerts:
`rp_due_backlog` staying above a few `batch-size` values (the worker cannot keep up), `rate(rp_fallback_total{outcome="failed"})`
or `expired` rising (receiver down, window too tight), and `up` of the worker role.

## Logs

One JSON object per line on stdout, using the project template `log4j2/ecs-reliable-push.json` (derived from the
Elastic Common Schema `EcsLayout.json`). Real examples:

```json
{"@timestamp":"2026-11-03T18:38:45.543Z","ecs.version":"1.2.0","log.level":"INFO","message":"notification.registered","process.thread.name":"tomcat-handler-0","log.logger":"io.reliablepush.event","trace.id":"f0cbf0632b07460193b03948a7d49595","event.action":"notification.registered","event.outcome":"success","rp.app_id":"shop","rp.tracking_id":"1be3e424-7f83-4057-94c1-c163ac1c352f","rp.user_hash":"6d894aa3ee802549","service.name":"reliable-push","service.version":"0.1.0-SNAPSHOT"}
{"@timestamp":"2026-11-03T18:39:49.841Z","ecs.version":"1.2.0","log.level":"INFO","message":"fallback.retry_scheduled","log.logger":"io.reliablepush.event","rp.tracking_id":"1be3e424-7f83-4057-94c1-c163ac1c352f","trace.id":"194ac21c44b242df8ae749b55a409e95","event.action":"fallback.retry_scheduled","event.duration":61942133,"event.outcome":"failure","reason":"io_error","rp.app_id":"shop","rp.attempt":1,"rp.next_attempt_at":"2026-11-03T18:40:49.837547Z","rp.user_hash":"6d894aa3ee802549"}
```

Fields: the template fields (`@timestamp`, `ecs.version`, `log.level`, `message`, `process.thread.name`, `log.logger`,
`service.name`, `service.version`, `error.type`/`error.message`/`error.stack_trace` for exceptions) plus the event fields
below, all top-level. ThreadContext entries `trace.id` and `rp.*` are top-level; any other ThreadContext entry appears under
`labels.*`. For catalogue events the `message` equals `event.action`.

- **Correlation:** `trace.id` is the trace id of a W3C `traceparent` request header, else a safe `X-Request-Id`, else a
  generated 32-hex id; it is echoed in the `X-Request-Id` response header. The worker uses a fresh `trace.id` and
  `rp.tracking_id` per claimed row, so one notification can be followed across API and worker logs by `rp.tracking_id`.
- **`rp.user_hash`:** the first 16 hex characters of SHA-256 of the user id; raw user ids are never logged.
- **Never logged:** API keys, ID tokens, signed grants, device tokens, HMAC secrets and signatures, the fallback content,
  request and response bodies ([security.md](security.md)).

### Event catalogue

`event.action` values emitted by the code (one line each; INFO unless noted):

| Event | Emitted when | Main fields |
|---|---|---|
| `api.request` | Every HTTP request completes | `http.request.method`, `url.path` (route template), `http.response.status_code`, `event.duration` (ns), `event.outcome` (`success` below 400), `rp.app_id`, `rp.device_id` when authenticated |
| `notification.registered` | A notification was inserted | `rp.app_id`, `rp.tracking_id`, `rp.user_hash` |
| `notification.duplicate` | A registration repeated an existing `externalId` | same |
| `notification.cancelled` | Sender cancelled a notification | `rp.app_id`, `rp.tracking_id` |
| `device.registered` | Device registered or re-registered | `rp.app_id`, `rp.device_id`, `rp.user_hash` |
| `device.token_refreshed` | Device token refreshed | same |
| `device.revoked` | Device revoked | same |
| `tracking.event_accepted` | An event was stored (also late ones) | `rp.event_type`, `rp.late`, `rp.tracking_id`, `rp.device_id`, `rp.app_id`, `rp.user_hash` |
| `tracking.event_ignored` | Duplicate event (`reason=duplicate`) | same |
| `auth.failed` (WARN) | Credential rejected | `reason` code only: `missing_key`, `unknown_key`, `missing_admin_key`, `unknown_admin_key` (admin API, header `X-Admin-Key`), `missing_token`, `malformed`, `unknown_kid`, `bad_signature`, `wrong_issuer`, `wrong_audience`, `expired`, `not_yet_valid`, `missing_user_claim`, `lifetime_too_long`, `jwks_unavailable`, `revoked`, `unknown_app` (a valid device token of an app no longer configured); `rp.app_id` for device registration |
| `authz.denied` (WARN) | Event for a `trackingId` of another user (`reason=tracking_id_of_another_user`) | `rp.app_id`, `rp.device_id`, `rp.user_hash`, `rp.tracking_id` |
| `rate_limit.exceeded` (WARN) | A bucket ran empty | `rate_limit.scope` = `sender`, `device`, `register` or `admin`, `rp.app_id`, `rp.device_id`, `rp.user_hash` or (admin) `rp.admin_key` |
| `admin.search` | Admin notification search answered | `rp.admin_key`, `rp.app_id`, `filters` = comma-separated filter names used (never values) |
| `admin.notification_viewed` | Admin opened a notification detail | `rp.admin_key`, `rp.app_id`, `rp.tracking_id`, `rp.user_hash` |
| `admin.payload_viewed` | Admin read a stored fallback payload (needs the `payload` permission) | same |
| `fallback.claimed` | The worker claimed a due row | `rp.app_id`, `rp.user_hash`, `rp.attempt` (number of the attempt about to be made) |
| `fallback.reclaimed` (WARN) | Claimed while the previous lease was still set: the previous holder crashed or was too slow | same |
| `fallback.dispatched` | Webhook answered `2xx`, row closed `FALLBACK_TRIGGERED` | `rp.attempt`, `event.duration`, `http.response.status_code` |
| `fallback.retry_scheduled` | Retryable failure, next attempt scheduled | `reason`, `rp.attempt`, `event.duration`, `rp.next_attempt_at`. WARN with `reason=app_not_configured` when the row's app is unknown on this instance |
| `fallback.failed` (WARN) | Final failure or attempts exhausted, `FALLBACK_FAILED` | `reason`, `rp.attempt`, `event.duration` |
| `fallback.expired` (WARN) | Closed as `FALLBACK_EXPIRED` | `reason` = `trigger_window_passed`, `next_retry_after_trigger_window` or `app_not_configured` |
| `retention.purged` | Retention job finished | `count` (also 0) |
| `retention.skipped` (WARN) | Retention did not run: admin is enabled, the rollup schedule is on and `daily_stats` has no row computed in the last 20 hours | `reason` = `rollup_missing` |
| `rollup.completed` | `DailyRollupJob` wrote the daily statistics | `count` = days written (not logged when another worker held the lock) |
| `rollup.failed` (WARN) | The rollup failed and rolled back | `reason` = exception class name |

Webhook failure `reason` codes: `http_<status>`, `timeout`, `io_error`, `interrupted`, `client_error`.

Other log lines (not catalogue events): ERROR with stack trace for unexpected exceptions (`Unhandled exception`, `Fallback
processing failed; the row is retried when its lease ends`, `Webhook client failed unexpectedly`); WARN `Fallback outcome ...
dropped: this worker's lease ran out and another worker re-claimed the row` (a stale worker's outcome was discarded; the
row keeps one final state); WARN `Due backlog refresh failed`; WARN `Database connection unavailable (<exception type>); answered 503 with Retry-After 5`
(no stack trace: the pool had no free connection within `connection-timeout`, see "Database pool" below); Log4j status-logger messages on stderr if logging itself
fails (logging never breaks a request).

Regenerate the list from the code with
`grep -rhoE '(EventLog\.(info|warn)\(|logEvent\(|logDevice\()"[a-z_.]+"' backend/src/main/java | sort -u`.

## Retention

`RetentionJob` (worker role) runs daily at 03:17 UTC (`reliable-push.retention.cron`, always UTC; `-` disables it) and deletes closed notifications whose `closed_at` is older than
`retention-days`, in chunks of 1,000 rows, each chunk its own short transaction; events go with them (`ON DELETE
CASCADE`). Notifications that are not closed are never deleted, whatever their age. Two replicas running it simultaneously
is harmless. It logs `retention.purged` with the count. Devices are not purged by this job.

Ordering with the rollup: `DailyRollupJob` (worker role, `admin.rollup.cron`, 03:07 UTC by default) runs before retention so
daily trends survive the notification retention. It recomputes the last `min(7, retention-days)` complete UTC days of every
configured app (zero rows included) under a PostgreSQL advisory lock (a second worker finds it taken and does nothing), deletes
`daily_stats` rows older than `admin.rollup-retention-days` and logs `rollup.completed` (or WARN `rollup.failed`). When admin
is enabled and `admin.rollup.cron` is not `-`, `RetentionJob` first requires a `daily_stats` row computed in the last 20 hours; otherwise
it deletes nothing and logs WARN `retention.skipped` with `reason=rollup_missing`, and the next night retries both. With
admin disabled, or the rollup schedule `-`, retention behaves as before.

## Scaling and capacity

- **Replicas:** run any number of `api` and `worker` replicas. Workers coordinate through `SKIP LOCKED` row claims and
  leases, so more workers raise throughput without duplicate dispatch (apart from the crash case below).
- **Rate limits are per instance** (in-memory buckets): with N replicas a caller can use up to N times the configured
  rate. Unauthenticated floods (garbage credentials on `POST /v1/devices`, invalid API keys) are not limited by the
  application; set **per-IP limits at the ingress**.
- **Database pool (API role):** each request holds one connection only for its SQL statements (a few milliseconds), but
  requests run on virtual threads, so concurrency is not bounded by a thread pool: under a burst they queue for the Hikari
  pool. A request that gets no connection within `spring.datasource.hikari.connection-timeout` (5 s here, Hikari's
  default is 30 s) is answered `503` with `Retry-After: 5` (logged as one WARN line, not ERROR), so clients back off
  instead of piling up. Size the pool as `maximum-pool-size ≈ peak requests per second × average connection time per
  request` with headroom (10 connections at ~5 ms each serve about 2,000 requests/s; measured against a database on the
  same host: about 0.15 ms per request, so 5 connections carried the NFR-2 load, see
  [Measured capacity](#measured-capacity); each network round trip to a remote database adds to it), and keep
  `replicas × maximum-pool-size` (plus the worker replicas' pools) below PostgreSQL `max_connections`. Frequent 503s with a
  healthy database mean the pool is too small or the replicas too few.
- **Database pool vs. worker batch:** every claimed row runs on its own virtual thread (up to `max-concurrency` at a
  time). The webhook call holds no database connection, but writing the outcome needs one, so up to `max-concurrency`
  rows can queue behind the Hikari pool (default 10 connections). A row that waits longer than `connection-timeout`
  (5 s) fails that write, is logged at ERROR and is re-sent after its lease, so on worker replicas raise
  `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` towards `max-concurrency / 10` or more (each write takes milliseconds;
  check PostgreSQL `max_connections` times the number of replicas), or lower `max-concurrency`. A slow receiver occupies
  virtual threads, not connections.
- **Worker throughput:** one tick claims for up to `lease / 2` (1 minute by default) while claims come back full, then
  waits for its calls in flight (at most one `http-timeout`) and the `poll-interval`. While a backlog lasts, one worker
  replica dispatches about `max-concurrency / average webhook latency` rows per second, times the duty cycle
  `(lease/2) / (lease/2 + http-timeout + poll-interval)` (about 80 % with the defaults). Examples with the defaults
  (`max-concurrency` 100): a 200 ms receiver gives about 400 rows/s, so a 90,000-row campaign that falls due at once
  is dispatched in about 4 minutes; a 2 s receiver gives about 40 rows/s, about 38 minutes, which is longer than the
  20-minute trigger window, so most of it would expire. The Helm chart sets `max-concurrency` 200, which helps most with
  slow receivers (the rate is latency-bound there); with the instant harness receiver the measured gain over 100 was
  about 17 % without a CPU limit (4,200 → 4,900 rows/s) and about 10 % at 1 CPU. Size for
  `backlog / (rows per second x replicas)` well inside `trigger-window-minutes`: raise `max-concurrency` (and the
  receiver's capacity), add worker replicas, or both.
  The formula holds only below the worker's own ceiling: with a receiver that answers at once, one replica limited to
  1 CPU dispatched about 3,100-3,400 rows/s, and a second replica did not help once PostgreSQL ran out of CPU
  ([Measured capacity](#measured-capacity)). `rp_due_backlog` shows how far behind the workers are.
- **Graceful stop:** on `SIGTERM` (Kubernetes rolling update, scale-down) a worker claims nothing more and finishes the
  webhook calls in flight, writing their outcomes before the connection pool closes, for at most
  `worker.shutdown-timeout`. A call that outlives it is logged once at WARN (`worker stop timed out; N rows may be
  re-sent after their lease`) and its row is re-sent after the lease. The stop is not cut by Spring's
  `spring.lifecycle.timeout-per-shutdown-phase` (`30s`, it bounds the web server's graceful drain); the hard bound is
  the kubelet's `SIGKILL`. Set `terminationGracePeriodSeconds` above
  `preStop 5 s (API pods) + worker.shutdown-timeout + 30 s` (the chart default is 60;
  [deployment.md](deployment.md#graceful-shutdown)) so the kubelet does not `SIGKILL` the pod first.
- **Crash re-sends:** if a worker dies after the receiver accepted a call but before the outcome was written, another
  worker re-sends once after the lease ends. Such re-sends are not counted against `max-attempts`. Receivers must
  de-duplicate on `Idempotency-Key` ([integration-guide.md](integration-guide.md)).
- **Mixed-version deployments:** migrations are forward-only and run at startup. `V3` adds `notification.lease_until`
  and a CHECK that only `DISPATCHING` rows hold a lease; older API instances are unaffected, but do not run pre-V3 and
  current workers side by side (an old worker that re-claims a row left with a lease would fail to close it). Replace worker
  replicas with a stop-then-start (`Recreate`) rollout across that boundary.
- **Config skew during rollouts:** a worker without an app's configuration leaves that app's due rows alone (retried
  after one lease) and expires them 20 minutes after `due_at`; add new apps to all replicas before sending traffic.
- **Fast clock:** only for local tests: `testing.minute-duration` scales the notification timeout, trigger window, webhook
  backoff and lease alike.

### Measured capacity

Measured on 2026-11-05 with `./scripts/load.sh` (local only, never in CI) on one Apple Silicon Mac (`Mac17,2`, 10 cores,
16 GiB) running Colima with 4 CPUs / 6 GiB: API container limited to 2 CPUs / 1 GiB, PostgreSQL 16 to 1 CPU, the Gatling
generator to 1 CPU on the same compose network (it averaged 9-13 % CPU during ingest and 32-54 % during bursts, so it
was never the limit), workers without a limit unless stated, database on the same host. All containers share 4 vCPUs.
**The numbers are relative to this machine**: use them as ratios and orders of magnitude, then confirm on your cluster.

**NFR-2 (API ingest): passed**, criterion and replica size unchanged. The criterion: one API replica limited to
2 CPUs / 1 GiB sustains 1,000 `POST /v1/events`/s (one event each) for 5 minutes after a 60 s ramp, alongside 500
`POST /v1/notifications`/s (open model, offered load fixed), with ≤ 0.1 % failed requests, p95 ≤ 100 ms and
p99 ≤ 250 ms per request group (`register`, `events`); a run whose generator averages more than 90 % CPU is `INVALID`,
not passed. Only an `ingest` entry of `harness/build/load/summary.json` with `"status": "PASSED"` **and**
`"config": {"nfr2Criterion": true}` validates NFR-2: `load.sh` sets the flag only when the run used the criterion's
settings (≥ 1,000 events/s, ≥ 500 notifications/s, 60 s ramp, ≥ 300 s steady, API container limited to 2 CPUs / 1g,
read from the container); shorter or slower runs still pass or fail their own assertions but prove nothing about
NFR-2. Results:

| API pool | register p95 / p99 | events p95 / p99 | failed | API CPU avg / max | API memory (RSS) |
|---|---|---|---|---|---|
| 5 | 1 / 34 ms | 2 / 38 ms | 0 % | 0.64 / 0.80 CPU | 275 MiB |
| 10 (default) | 1 / 30 ms | 2 / 38 ms | 0 % | 0.61 / 0.70 CPU | 264 MiB |
| 20 | 1 / 28 ms | 2 / 36 ms | 0 % | 0.62 / 0.69 CPU | 275 MiB |

- The replica used about a third of its CPU limit; PostgreSQL about 0.35 CPU. The heap stayed below 90 MiB (non-heap
  about 125 MiB); with a 1 GiB limit the JVM picks the Serial collector, which kept p99 within 40 ms.
- A request held pool connections for about 0.15 ms in total (1.7 checkouts of about 0.09 ms each): pool size made no
  difference between 5 and 20. A database across the network multiplies this by its round trips.
- **Warm-up:** right after start, an API replica answered a 1,500 notifications/s burst with p99 400-460 ms (JIT still
  compiling); after one minute of traffic, 16-136 ms. Expect slower answers from new replicas under peak load.

**Fallback burst (worker).** 30,000 `NOT_DELIVERED` notifications at 1,500/s that fall due one minute later. The harness
receiver answers at once, so these are upper bounds for the worker and database side; with a real receiver its latency
is usually the limit (formula above).

- **Arrival-paced:** with every combination of `max-concurrency` 50 / 100 / 200 and worker pool 10 / 20, and with two
  workers, one worker kept pace with the arrivals: 1,477-1,557 rows/s, all 30,000 triggered, none failed or expired,
  sampled `rp_due_backlog` at most 1,358 rows (under 600 in most runs), about 0.4 CPU on the worker.
- **Backlog due at once** (worker container paused during the burst until every row was due, then resumed):

| Worker(s) | `max-concurrency` | pool | rows | rows/s overall (best 10 s) | drain |
|---|---|---|---|---|---|
| 1, no limit | 50 | 10 / 20 | 30,000 | 2,945 / 2,956 | 10.2 s |
| 1, no limit | 100 | 10 / 20 | 30,000 | 4,211 / 4,202 | 7.1 s |
| 1, no limit | 100 | 10 | 60,000 | 3,496 (4,055) | 17.2 s |
| 1, no limit | 200 | 10 / 20 | 30,000 | 4,903 / 4,902 | 6.1 s |
| 1, no limit | 200 | 20 | 60,000 | 4,907 (5,290) | 12.2 s |
| 1, 1 CPU / 768 MiB | 100 | 10 | 60,000 | 3,104 (4,030) | 19.3 s |
| 1, 1 CPU / 768 MiB | 200 | 20 | 60,000 | 3,435 (4,242) | 17.5 s |
| 1, 1 CPU / 512 MiB | 200 | 20 | 60,000 | 3,252 (4,291) | 18.4 s |
| 2, no limit | 200 | 20 | 60,000 | 4,102 (4,632) | 14.6 s |

- No run failed or expired a row. The worker pool (10 vs 20) made no difference: only the outcome write holds a
  connection. `max-concurrency` mattered until the worker ran out of CPU: without a limit one worker used up to 1.7 CPU;
  at 1 CPU it ran at its limit and 200 gave about 10 % more than 100. The worker's RSS stayed at 250-270 MiB with a
  768 MiB or 512 MiB limit.
- PostgreSQL (1 CPU) ran at or near its limit in every drain, so a second worker did not add throughput: past a few
  thousand rows/s, give the database more CPU before adding worker replicas.
- With this receiver a row was in flight for about 17 ms (50), 24 ms (100) and 41 ms (200), i.e.
  `max-concurrency / rows per second`, mostly CPU and database time; it adds to the receiver latency in the formula.

The Helm chart defaults follow these runs (`deploy/helm/reliable-push/values.yaml`): API pool 10, requests
800m / 384Mi (measured CPU and RSS at the NFR-2 load + 30 %), limits 2 CPUs / 1Gi (the NFR-2 size); worker
`max-concurrency` 200, pool 20, requests 500m / 384Mi, limits 1 CPU / 512Mi. In `combined` mode the pods run both roles
with `api.resources`: add the worker's requests.

**Reproducing** (from the repository root; results land in `harness/build/load/summary.json`):

- NFR-2 rows: `./scripts/load.sh --simulation ingest --api-pool 5` (and `10`, `20`); the defaults implement the
  criterion (`nfr2Criterion: true` in `summary.json`).
- Arrival-paced burst rows: `LOAD_RAMP=10s LOAD_STEADY=50s ./scripts/load.sh --simulation all --worker-concurrency 200
  --worker-pool 20`, with the row's `--worker-concurrency` / `--worker-pool` (add `--workers 2` for two workers)
  (`nfr2Criterion: false` there: the ingest is shortened). `--simulation burst` alone also works on a freshly started
  stack: the burst is preceded by a warm-up (`LOAD_BURST_WARMUP`, default 30 s, ramping up to the burst rate under the
  request name `notify-warm-up`, not asserted; its notifications fall due after 60 minutes), so the cold JIT no longer
  fails the measured `notify` p99 assertion.
- "Backlog due at once" table (30,000- and 60,000-row runs): **not reproducible with `load.sh`
  alone**: they come from pausing the worker container by hand. Run the arrival-paced command (with
  `LOAD_BURST_SIZE=60000` for the 60,000-row runs) and, in a second shell: when `load.sh` prints
  `running FallbackBurstSimulation`, run `docker pause reliable-push-load-worker-a-1` (and
  `reliable-push-load-worker-b-1` with two workers); when `harness/build/load/logs/gatling-burst.log` shows
  `Generating reports`, wait 65 s (every row is then due) and `docker unpause` the same containers. The drain probe
  measures from the first trigger; its `maxBacklog` reads 0 there because the paused worker publishes the backlog
  gauge.
- Rows with a worker limit: add `LOAD_WORKER_CPUS=1 LOAD_WORKER_MEMORY=768m` (or `512m`) to the command.
- If the generator reports `Cannot assign requested address`, it ran out of ephemeral ports (each virtual user opens
  its own connection); the `sysctls` of the `gatling` service in `loadtest/compose/load.override.yml` fix it, so keep
  them in any modified setup.

## Container image

Built from `backend/Dockerfile` (`./mvnw -B verify -Pimage` or `docker build -t reliable-push-backend:dev backend`): a
jlink-trimmed Temurin 21 JRE on a pinned Alpine, about 105 MB uncompressed (the budget of 250 MB is checked as the sum of
the uncompressed layer sizes). Listens on 8080, exec-form entrypoint (the JVM is PID 1), no Docker `HEALTHCHECK` (use
the Kubernetes probes).

- **Arbitrary UID:** runs as UID 1001 by default, and also as a random UID in group 0 (OpenShift); application files are
  group-writable (`g=u`). The image tests run it as UID 1000870000.
- **Read-only root filesystem** works; the only writable path needed is `/tmp` (use a tmpfs/emptyDir).
- **Baked JVM options:** `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`. Setting
  `JAVA_TOOL_OPTIONS` yourself replaces these defaults, so repeat them.
- **Custom CA certificates** (for a private-CA webhook receiver or identity provider): mount a truststore and set
  `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djavax.net.ssl.trustStore=/certs/truststore.jks -Djavax.net.ssl.trustStorePassword=<from a secret>"`.
  The image ships the standard CA bundle (`cacerts`) for public HTTPS endpoints.
- **Diagnostics:** the trimmed JRE has no `jcmd`/`jstack`; use Java Flight Recorder (`-XX:StartFlightRecording`) or add
  diagnostics in a derived image.
- **Locale data:** English only (the `jdk.localedata` module is not included).
- **Example (read-only, arbitrary UID):**
  `docker run --read-only --tmpfs /tmp --user 1000870000:0 -p 8080:8080 ... reliable-push-backend:<version>`.

## CI notes (GitHub Actions)

- `.github/workflows/backend-ci.yml`: `./mvnw -B verify` (unit tests, integration tests with Testcontainers, JaCoCo gate at
  80% line coverage), then the image job (`-Pimage`, Trivy scan for CRITICAL/HIGH fixed vulnerabilities), plus dependency
  review on pull requests. End-to-end tests never run in CI.
- `.github/workflows/codeql.yml`: CodeQL for Java; also scheduled weekly.
- `.github/workflows/harness-ci.yml`: `client-java`, `harness` (`-am`) and the `loadtest` job
  (`./mvnw -B -pl loadtest verify`: compiles the Gatling simulations and runs the module's unit tests, no load run).
- `.github/workflows/helm-ci.yml`: `deploy/helm/scripts/validate.sh` (`helm lint --strict`, helm-unittest, kubeconform)
  with the pinned images of `deploy/helm/tools.env`. The kind smoke install and the load runs are local only.
- **Scheduled runs only start from the default branch.** Dependabot (`.github/dependabot.yml`) and the CodeQL `schedule`
  use the configuration on the repository's default branch; changes merged only to `develop` take effect for them once
  they reach the default branch.
- **Path-filtered workflows and required checks:** every workflow runs only when its own paths change (`backend/**`
  for backend-ci and CodeQL; `client-java/**`, `harness/**`, `loadtest/**` and the root Maven files for harness-ci;
  `deploy/helm/**` for helm-ci; or the workflow file). A path-filtered workflow that does not run reports no status, so
  marking one of its jobs as a *required* status check blocks every pull request that does not touch those paths. Either
  do not require those checks, or add an always-running aggregate job that is the only required check.
- **CodeQL on fork and Dependabot pull requests:** the token is read-only there, so uploading the SARIF results fails
  (`security-events: write` is not granted). Treat that failure as expected for those PRs, or run CodeQL for them on push
  after merge.
- Local equivalent: `cd backend && ./mvnw -B verify` and `./mvnw -B verify -Pimage` ([testing.md](testing.md)).
