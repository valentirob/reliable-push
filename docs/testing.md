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
| End-to-end | `harness/`, `./scripts/e2e.sh` | local only, never in CI | **Arrives with plan 1B.** Full scenarios across backend, PostgreSQL, fake OIDC, fallback receiver and device simulator, with the fast clock |
| Console smoke | Playwright for Java against the harness web console | local only | **Arrives with plan 1B** |
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

## End-to-end tests and the web console (arrives with plan 1B)

The local harness (`harness/`, `./scripts/e2e.sh`, `./scripts/demo.sh`) is planned for plan 1B; until then neither command
exists. Once it lands, this page will describe the scenarios, the machine-readable results and the agent feedback loop
described in spec 10.1 and in `.agents/skills/run-e2e/SKILL.md`.
