# Testing

The strategy is in spec section 10. This page covers what exists for the backend today and how to run it.

## Prerequisites

- JDK 21 and Docker (Testcontainers starts PostgreSQL 16; the image tests build and run the container image).
- Use the Maven wrapper from `backend/`. With Colima or another non-default Docker socket, export the usual variables
  first, for example `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

## Commands

| Goal | Command (run in `backend/`) |
|---|---|
| Everything the CI build runs: unit tests, integration tests, JaCoCo gate | `./mvnw -B verify` |
| Container image build + image tests only | `./mvnw -B verify -Pimage` |
| Unit tests only | `./mvnw -B test` |
| One unit test class or method | `./mvnw -B test -Dtest=FallbackDecisionTest` / `-Dtest=FallbackDecisionTest#methodName` |
| One integration test class | `./mvnw -B verify -Dit.test=NotificationApiIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` |

Reports: `target/surefire-reports/` (unit), `target/failsafe-reports/` (integration), coverage in
`target/site/jacoco/index.html` (and `jacoco.csv`). The build fails if the line coverage of the bundle drops below 80%
(`jacoco:check`); the current figure is recorded in [STATUS.md](STATUS.md).

## Test levels

| Level | Naming / tooling | Runs in | Covers |
|---|---|---|---|
| Unit | `*Test`, JUnit 5 + AssertJ, Mockito as a Java agent | surefire (`test`) | Pure logic: `FallbackDecision` (trigger window, retry and backoff rules), `WebhookSigner`, device token issue and strict verification, signing-key parsing, identity verifiers (`SignedGrantVerifier`, URL rules), rate limiter, `TimeScale`, secret references, configuration validation, `EventLog` and the ECS template, `RpMetrics` |
| Integration | `*IT`, extends `PostgresTestBase` (the full application on a random port against a real PostgreSQL 16 Testcontainer, no database mocking), WireMock for webhook receivers, `FakeOidc` for an OIDC issuer and JWKS | failsafe (`integration-test`) | REST endpoints (`NotificationApiIT`, `DeviceApiIT`, `EventsApiIT`, `SenderApiKeyIT`, `ProblemHandlerIT`), schema and constraints (`SchemaIT`), the fallback worker with concurrent claiming, leases, re-claim and fenced outcomes (`FallbackSchedulerIT`), the signed webhook client against WireMock (`HttpWebhookClientIT`), retention (`RetentionJobIT`), access log and JSON output (`AccessLogIT`, `JsonLogOutputIT`), metrics (`MetricsIT`), OIDC verification and JWKS caching (`OidcIdTokenVerifierIT`), application startup (`ApplicationStartsIT`) |
| Contract | `OpenApiContractTest` and `OpenApiAssert` (swagger-request-validator, test scope) | surefire + the ITs | Real responses are validated against `backend/src/main/resources/openapi.yaml`, so the contract and the implementation cannot drift |
| Container image | `ContainerImageIT`, tagged `image` | failsafe, only with `-Pimage` | Starts as an arbitrary non-root UID, stays below the 250 MB budget (sum of uncompressed layers), read-only root filesystem with a writable `/tmp`, JSON-only stdout |
| Logging assertions | inside the ITs and `EcsTemplateTest` | both | Catalogue events are emitted, fields are unique, and no secrets, tokens, user ids or message content reach the log |
| Client | `client-java/`, JUnit 5 against a local HTTP server | surefire | The Java sender client: requests, problem details, timeouts, redirects, `Retry-After`; an enforcer rule keeps it free of runtime dependencies |
| Harness | `harness/`, JUnit 5, `*Test` and `*IT` (Spring on a random port, no Docker needed) | surefire / failsafe | The harness itself: device simulator, signed grants, fallback receiver, fake sender, tracking poller, scenario runner, console REST and SSE |
| End-to-end | `harness/`, `./scripts/e2e.sh` | local only, never in CI | 16 scenarios across the real backend (one API, two workers), PostgreSQL, the fallback receiver and the device simulator, with the fast clock |
| Console smoke | Playwright for Java against the harness web console | local only | The 17th entry of the E2E run: a headless browser registers a device, sends a push and sees `DELIVERED` and the fallback in the inbox |
| Load | `loadtest/`, Gatling (Java DSL), `./scripts/load.sh` | local only, never in CI (CI compiles the simulations and runs the module's unit tests) | NFR-2 ingest (`EventIngestSimulation`) and the fallback burst drained by the workers (`FallbackBurstSimulation` + `BurstDrainProbe`) |
| Helm chart | helm-unittest, `helm lint --strict`, kubeconform (`deploy/helm/scripts/validate.sh`); kind smoke install (`./scripts/helm-smoke.sh`) | validate: CI and local; kind: local only | Required values and secret rules, rendering per mode and platform, schemas; a real install with a worker rollout during dispatch |
| Mobile | Robolectric, XCTest, emulator/simulator tests | per SDK | Planned in cycles 2 and 3 |

## Notes on how the suites work

- **Default build vs. image tests:** `-Pimage` flips the failsafe groups: the default build excludes tests tagged `image`,
  the `image` profile runs only those (after `docker build` in `pre-integration-test`) and skips the JaCoCo gate. CI runs
  `./mvnw -B verify -Pimage -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false` so surefire does not repeat the
  unit tests.
- **One shared database:** all ITs share a JVM-wide PostgreSQL container; tests create their own users and tracking ids
  instead of cleaning tables, so they can run in any order.
- **Test configuration:** `src/test/resources/application-test.yml` (profile `test`) holds throwaway apps and obviously fake
  secrets; the device signing keys are generated per JVM and never written to disk.
- **Time in tests:** the worker ITs use a controllable `MutableClock` and the fast clock (1 product minute = 1 s) instead of waiting for real minutes.
- **Clean output:** tests are expected to finish without stray stack traces or WARN lines from the code under test.

## Client and harness builds

Run from the repository root (one Maven wrapper for `client-java` and `harness`; the backend keeps `backend/mvnw`):

| Goal | Command |
|---|---|
| Client build and tests (what CI runs) | `./mvnw -B -pl client-java verify` |
| Harness build and tests, including `client-java` (what CI runs) | `./mvnw -B -pl harness -am verify` |
| Console smoke test against a running stack (needs Playwright's Chromium, about 150 MB, downloaded on first use) | `./mvnw -B -pl harness -am verify -Pconsole-smoke` |
| Loadtest module: compiles the simulations, runs the unit tests, never starts Gatling (what CI runs) | `./mvnw -B -pl loadtest verify` |

The default build never downloads Playwright. GitHub Actions runs the client, harness and loadtest builds
(`.github/workflows/harness-ci.yml`); it never runs the stack or a load test.

## End-to-end tests (local only)

`./scripts/e2e.sh [--scenario <name>] [--keep]` (Windows, PowerShell 7: `pwsh scripts/e2e.ps1 [-Scenario <name>] [-Keep]`; the
PowerShell scripts have not been run on Windows). Needs Docker with the compose plugin, `curl`, and ports 8080 and 8090 free
on 127.0.0.1. The images build inside Docker, but `console-smoke` (so the full 17-entry run) calls the host `./mvnw`: it needs
JDK 21 and network access (Maven artifacts, Playwright's Chromium). With Colima: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.

- **Stack:** `harness/compose/docker-compose.yml`: PostgreSQL, one API, two workers (`worker-a`, `worker-b`) and the
  harness. A one-shot container generates new secrets for every run into `harness/build/e2e/secrets.env` (git-ignored).
  Devices register through the backend's `signed-grant` identity (the harness signs the grants).
- **Fast clock:** 1 product minute = 1 s, worker poll 500 ms, lease 12 minutes (= 12 s). The whole run takes about 5 minutes.
- **Scenarios** (16, plus `console-smoke`): `delivered-before-timeout`, `read-policy-delivered-only`, `read-policy-read`,
  `not-delivered-single-fallback`, `late-delivery-after-fallback`, `multi-device`, `cancellation`,
  `token-refresh-near-expiry`, `rate-limited`, `receiver-retries-then-succeeds`, `receiver-final-failure`,
  `retries-exhausted-fails`, `worker-crash-inside-window`, `worker-crash-outside-window`, `log-catalogue`, `log-hygiene`.
- **Worker-crash scenarios** are driven by the script, not the harness container: it stops `worker-b`, asks the harness to
  `prepare`, waits until the webhook call is in flight, kills `worker-a`, waits (inside or outside the trigger window), starts
  both workers and asks the harness to `verify`.
- **Results:** exit code 0 only if all 17 entries PASSED. `harness/build/e2e/summary.json`
  (`{scenario, status, durationMs, failure: {expected, actual, hint}, logs}`), JUnit XML in `harness/build/e2e/junit/`,
  container logs and the backend JSON logs in `harness/build/e2e/logs/`. Logs are collected and the stack is torn down even
  when scenarios fail (unless `--keep`).
- **Failures:** `grep -B2 -A6 '"FAILED"' harness/build/e2e/summary.json`, then search the logs by `trace.id` or
  `rp.tracking_id`.
- **Safety checks:** the script refuses to start if port 8080 or 8090 is taken (it names the port) and if a stack of an earlier
  `--keep` run exists it removes it first. If a service is not ready within `E2E_READY_TIMEOUT` seconds (default 120) it names
  the container and prints its last log lines.
- The agent workflow is in [`.agents/skills/run-e2e/SKILL.md`](../.agents/skills/run-e2e/SKILL.md).

## Web console and demo

`./scripts/demo.sh [--realtime]` (Windows: `scripts\demo.ps1`) starts the same stack as the compose project
`reliable-push-demo` and opens the console at <http://127.0.0.1:8090/>. The default is the fast clock; `--realtime` uses real
minutes. It keeps running; the script prints the stop command:
`docker compose -p reliable-push-demo -f harness/compose/docker-compose.yml -f harness/compose/demo.override.yml down -v`.

The console has six screens: Devices, Send push, Live tracking, Fallback inbox, Scenarios and Logs. A permanent banner reads
"Test harness — simulated data, not for production". No secret is shown in the page or exposed by its API; live updates use
Server-Sent Events and reconnect automatically. The console API accepts writes (`POST`) only with `Content-Type: application/json`
(an explicit `{}` when there is nothing to send) and rejects cross-site requests (`Origin` or `Sec-Fetch-Site` from another
origin) with 403. Every `/console/api/` request (reads too) must also carry a `Host` of `localhost`, `127.0.0.1`, `[::1]`,
`10.0.2.2` or `harness` (any port), else 403, which defeats DNS rebinding. `curl`, the scripts and the emulator's `HttpURLConnection` send neither cross-site header. The console and the harness are never built into the backend
image or deployed with a Helm chart.

For real apps (the Android sample on an emulator, which reaches the host harness at `10.0.2.2:8090`) the console API also has
`POST /console/api/grants {appId, userId}` (201 `{grant, expiresAt}`: a signed grant, minted by the same component as the
simulator's; the only endpoint that returns one, never logged) and `POST /console/api/real-devices {appId, userId, deviceId,
platform, label}` (201; kept in memory, at most 50). Real devices show in the Devices list as `REAL` (the harness holds no
token for them, so no refresh/offline buttons); `GET /console/api/devices` marks every entry `"kind": "REAL"` or
`"SIMULATED"`. The push preview also shows the `adb shell am broadcast` line for the sample app's debug receiver; its values
are single-quoted twice (host shell and device shell), so paste it into a POSIX host shell (not PowerShell or cmd).

## Load tests (local only)

`./scripts/load.sh [--simulation ingest|burst|all] [--api-pool N] [--worker-concurrency N] [--worker-pool N] [--workers 1|2] [--keep]`
(defaults: `all`, 10, 100, 10, 1). Never run in CI. Needs Docker with the compose plugin and ports 8080 and 8090 free on
127.0.0.1, so it cannot run next to the E2E or demo stack. `--simulation all` takes about 15 minutes.

- **Stack:** the E2E compose file plus `loadtest/compose/load.override.yml` (project `reliable-push-load`, real clock, API
  limited to 2 CPUs / 1 GiB, workers on the management port 8081) and a Gatling container on the same compose network,
  so host port forwarding never caps the throughput. Its Maven cache is the external volume `reliable-push-gatling-m2`.
- **Simulations:** `ingest` is the NFR-2 criterion: 500 `POST /v1/notifications`/s and 1,000 `POST /v1/events`/s (one
  event each) for 5 minutes after a 60 s ramp, asserting ≤ 0.1 % failed requests, p95 ≤ 100 ms and p99 ≤ 250 ms per
  request group. `burst` registers `LOAD_BURST_SIZE` notifications (default 30,000 at 1,500/s) that fall due together;
  `BurstDrainProbe` samples the workers' `/actuator/prometheus` until all are triggered; a warm-up before the burst
  (`LOAD_BURST_WARMUP`, default 30 s, request name `notify-warm-up`) keeps the cold JIT out of the asserted `notify`
  requests, so `burst` alone works on a fresh stack. `all` runs ingest first.
- **Results:** `harness/build/load/summary.json` (per simulation: `status` `PASSED`/`FAILED`/`INVALID`, metrics, the
  effective config with `nfr2Criterion`, generator CPU), Gatling HTML reports in `harness/build/load/gatling/`, the probe
  output in `burst.json`, container logs in `logs/`. Exit code 0 only if every simulation `PASSED`. A run whose Gatling
  container averages more than 90 % CPU is `INVALID` (the generator, not the backend, was the limit). Only an `ingest`
  entry with `status` `PASSED` and `config.nfr2Criterion` `true` (the run used the NFR-2 rates, 60 s ramp, ≥ 300 s
  steady, API at 2 CPUs / 1g) validates NFR-2.
- **Settings:** environment variables `LOAD_DEVICES`, `LOAD_NOTIFICATIONS_PER_SECOND`, `LOAD_RAMP`, `LOAD_STEADY`,
  `LOAD_BURST_SIZE` (a multiple of `LOAD_BURST_PER_SECOND`), `LOAD_BURST_PER_SECOND`, `LOAD_BURST_WARMUP`,
  `LOAD_READY_TIMEOUT`, `LOAD_WORKER_CPUS`, `LOAD_WORKER_MEMORY`. Details of the module: [`loadtest/README.md`](../loadtest/README.md).
- **Measured results** and how to reproduce each table row: [operations.md, "Measured capacity"](operations.md#measured-capacity).

## Helm chart checks

- **`deploy/helm/scripts/validate.sh`** (Docker only; CI runs it in `.github/workflows/helm-ci.yml`): `helm lint --strict`
  in both modes, the helm-unittest suites in `deploy/helm/reliable-push/tests/`, and `helm template | kubeconform -strict`
  for four renders (default, split, GKE overlay, OpenShift overlay) against the Kubernetes 1.30 schemas, the vendored Route
  schema (`deploy/helm/schemas/`) and the CRD catalog at a pinned commit (ServiceMonitor, PodMonitoring, BackendConfig;
  fetched over the network). Tool images are pinned by digest in `deploy/helm/tools.env`; nothing is installed on the
  host.
- **`./scripts/helm-smoke.sh [--keep]`** (local only, never in CI; needs kind, kubectl, curl, JDK 21): creates the kind
  cluster `rp-smoke` (node image from `tools.env`), builds and loads the backend image, installs PostgreSQL, a slow dummy
  fallback receiver and the chart in split mode (1 API, 2 workers, UID 1000870000 / GID 0 like OpenShift), then checks the
  rollouts, `helm test`, the API through its Service, `/actuator` answering 404 on the API port, and a worker
  `kubectl rollout restart` while webhook calls are in flight (all 200 rows triggered exactly once, no ERROR log, no
  `fallback.reclaimed`). Each check prints `PASS`/`FAIL`; exit 0 = passed. It uses its own kubeconfig in
  `harness/build/helm-smoke/` and never touches `~/.kube/config`; helm runs from `HELM_IMAGE`. Without `--keep` the
  cluster is deleted at the end; with `--keep` delete it with
  `kind delete cluster --name rp-smoke --kubeconfig harness/build/helm-smoke/kubeconfig`. A kept cluster is marked with
  its control-plane container ID: the next run reuses (and may delete) only that cluster, and refuses an `rp-smoke`
  cluster it did not create. Each run starts with empty `logs/`.
