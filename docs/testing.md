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
| Load | Gatling | local / on demand | Arrives with plan 1C |
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

The default build never downloads Playwright. GitHub Actions runs the first two (`.github/workflows/harness-ci.yml`); it never
runs the stack.

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
origin) with 403; `curl` and the scripts send neither header. The console and the harness are never built into the backend
image or deployed with a Helm chart.
