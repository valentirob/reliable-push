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
| `e2e`, `demo` | Allow the fast clock (`reliable-push.testing.minute-duration`), used by the local harness only |

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
| `worker.batch-size` | `100` | 1-10000 rows claimed per tick (each on its own virtual thread) |
| `worker.lease` | `2m` | A product duration (scaled by the fast clock); a bare number means minutes. Must be longer than every app's `fallback.http-timeout`; otherwise startup fails (a warning only with the fast clock) |
| `worker.backlog-refresh-interval` | `30s` | Optional; how often `rp_due_backlog` is recomputed |
| `retention-days` | `30` | At least 1. Closed notifications older than this are deleted daily |
| `retention.cron` | `0 17 3 * * *` | Spring cron expression (six fields, always UTC) of the daily retention run (worker role); `-` disables the schedule |
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
  `health` and `prometheus` are exposed under `/actuator`. They share the application port: do not route `/actuator/**`
  through a public ingress; scrape and probe from inside the cluster.
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
| `auth.failed` (WARN) | Credential rejected | `reason` code only: `missing_key`, `unknown_key`, `missing_token`, `malformed`, `unknown_kid`, `bad_signature`, `wrong_issuer`, `wrong_audience`, `expired`, `not_yet_valid`, `missing_user_claim`, `lifetime_too_long`, `jwks_unavailable`, `revoked`, `unknown_app` (a valid device token of an app no longer configured); `rp.app_id` for device registration |
| `authz.denied` (WARN) | Event for a `trackingId` of another user (`reason=tracking_id_of_another_user`) | `rp.app_id`, `rp.device_id`, `rp.user_hash`, `rp.tracking_id` |
| `rate_limit.exceeded` (WARN) | A bucket ran empty | `rate_limit.scope` = `sender`, `device` or `register`, `rp.app_id`, `rp.device_id` or `rp.user_hash` |
| `fallback.claimed` | The worker claimed a due row | `rp.app_id`, `rp.user_hash`, `rp.attempt` (number of the attempt about to be made) |
| `fallback.reclaimed` (WARN) | Claimed while the previous lease was still set: the previous holder crashed or was too slow | same |
| `fallback.dispatched` | Webhook answered `2xx`, row closed `FALLBACK_TRIGGERED` | `rp.attempt`, `event.duration`, `http.response.status_code` |
| `fallback.retry_scheduled` | Retryable failure, next attempt scheduled | `reason`, `rp.attempt`, `event.duration`, `rp.next_attempt_at`. WARN with `reason=app_not_configured` when the row's app is unknown on this instance |
| `fallback.failed` (WARN) | Final failure or attempts exhausted, `FALLBACK_FAILED` | `reason`, `rp.attempt`, `event.duration` |
| `fallback.expired` (WARN) | Closed as `FALLBACK_EXPIRED` | `reason` = `trigger_window_passed`, `next_retry_after_trigger_window` or `app_not_configured` |
| `retention.purged` | Retention job finished | `count` (also 0) |

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
  request` with headroom (10 connections at ~5 ms each serve about 2,000 requests/s), and keep
  `replicas × maximum-pool-size` (plus the worker replicas' pools) below PostgreSQL `max_connections`. Frequent 503s with a
  healthy database mean the pool is too small or the replicas too few.
- **Database pool vs. worker batch:** every claimed row runs on its own virtual thread (up to `batch-size`). The webhook
  call holds no database connection, but writing the outcome needs one, so a batch of 100 rows can queue behind Spring's
  default Hikari pool of 10 connections (default wait 30 s). Raise `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` (check
  PostgreSQL `max_connections` times the number of replicas) or lower `batch-size`. A slow receiver occupies virtual
  threads, not connections; one tick lasts about one `http-timeout` at most.
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
- **Scheduled runs only start from the default branch.** Dependabot (`.github/dependabot.yml`) and the CodeQL `schedule`
  use the configuration on the repository's default branch; changes merged only to `develop` take effect for them once
  they reach the default branch.
- **Path-filtered workflows and required checks:** both workflows only run when `backend/**` (or the workflow file)
  changes. A path-filtered workflow that does not run reports no status, so marking one of its jobs as a *required* status
  check blocks every pull request that does not touch the backend. Either do not require those checks, or add an
  always-running aggregate job that is the only required check.
- **CodeQL on fork and Dependabot pull requests:** the token is read-only there, so uploading the SARIF results fails
  (`security-events: write` is not granted). Treat that failure as expected for those PRs, or run CodeQL for them on push
  after merge.
- Local equivalent: `cd backend && ./mvnw -B verify` and `./mvnw -B verify -Pimage` ([testing.md](testing.md)).
