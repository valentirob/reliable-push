# Cycle 1C — Helm Chart, Load Tests and Capacity Sizing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Non-Claude tools: use `.agents/skills/implement-task`. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy the backend on GKE and OpenShift with one Helm chart, validate NFR-2 (≥ 1,000 events/s per API replica) and the worker throughput with local Gatling load tests, and publish measured pool and replica sizing.

**Architecture:** Two small backend changes make the service Kubernetes-ready: an opt-in management port (probes and metrics off the public port) and a graceful worker stop (no claim after `SIGTERM`, rows in flight finish before the pool closes). `deploy/helm/reliable-push` renders one combined Deployment or split `api`/`worker` Deployments, with Ingress (GKE) or Route (OpenShift), validated by helm-unittest and kubeconform run from pinned container images, plus a local kind smoke install. `loadtest/` is a Gatling (Java DSL) module run only locally, in a container on the network of the existing harness compose stack (`scripts/load.sh`), with a small drain probe for the worker burst; its results feed the sizing tables in `docs/operations.md` and the chart defaults.

**Tech Stack:** Java 21, Spring Boot 4.1.x (backend), Helm 3/4-compatible chart `apiVersion: v2`, Kubernetes ≥ 1.30, helm-unittest, kubeconform, kind, Gatling 3.x Java DSL (Maven plugin, test scope), Docker Compose v2.

**Spec:** `docs/specs/2026-11-03-reliable-push-design.md` (§1 roles, §5 rate limits and webhook timeout, §9 operations and metrics, §9.2 container and Helm, §10 load row, §11 CI, §12 layout). Requirements: NFR-2, NFR-3, NFR-4, NFR-7, NFR-9. Prior follow-ups (`docs/STATUS.md` 2026-11-04): graceful worker shutdown, `/actuator/prometheus` on a management port.

**Follow-up (not in this plan):** container image publishing on release tags (GHCR), NetworkPolicy template, shared rate-limit store, Android SDK cycle.

## Global Constraints

- The chart deploys **only the backend**: never the harness, the console or the load generator (spec §10.2). No secret value ever appears in a values file or template: secrets come from one existing Kubernetes Secret (`secrets.existingSecret`, required), loaded with `envFrom`.
- Containers: non-root, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault`, writable `/tmp` as `emptyDir` (spec §9.2). OpenShift values set **no** `runAsUser`, `runAsGroup` or `fsGroup` (restricted-v2 SCC assigns an arbitrary UID in group 0); GKE values set `runAsUser: 1001`, `runAsGroup: 0`.
- Kubernetes ≥ 1.30 (`kubeVersion: ">=1.30.0-0"`); OpenShift ≥ 4.17. Ports: `http` 8080 (API), `management` 8081 (probes, Prometheus; never routed by Ingress/Route).
- Load tests and the kind smoke install are **local only**, like E2E: never run in CI. CI may lint/unit-test the chart and compile/unit-test `loadtest/` (spec §11).
- NFR-2 pass criterion (open workload model, offered load fixed): one API replica limited to **2 CPUs / 1 GiB** sustains **1,000 `POST /v1/events` requests/s (one event each) for 5 minutes** after a 60 s ramp, alongside 500 `POST /v1/notifications`/s, with **≤ 0.1 % failed requests, p95 ≤ 100 ms, p99 ≤ 250 ms** per request group. A run whose Gatling container averages > 90 % CPU is reported `INVALID` (generator saturated), not passed.
- Dependency budget (spec §9.2): the backend gains **no** dependency; Gatling is test scope in `loadtest/` only; `loadtest/` main code is JDK only.
- Tool versions are pinned once (Task 0) in `deploy/helm/tools.env` (`HELM_IMAGE`, `HELM_UNITTEST_IMAGE`, `KUBECONFORM_IMAGE`, `KIND_NODE_IMAGE`, all `name:tag@sha256:…`) and in `loadtest/pom.xml` (Gatling): the latest release at least two weeks old. Scripts read `tools.env`; nothing uses `latest`.
- The load stack reuses `harness/compose/docker-compose.yml` with `loadtest/compose/load.override.yml` (project `reliable-push-load`, generated files in `harness/build/load/`, real clock: no `RELIABLEPUSH_TESTING_MINUTEDURATION`). Published ports bind `127.0.0.1` only (8080, 8090); it cannot run next to the E2E/demo stack (fail fast on busy ports).
- Packages: `io.reliablepush.loadtest` (`loadtest/`). Backend changes stay in the existing modules (`fallback`, `shared`).
- Gitflow: all tasks commit to `feature/cycle1c-deploy-load` from `develop`, merged `--no-ff` after the final review; Conventional Commits; tick the task's checkboxes in this file in the same commit. Merge line reports `E2E: 17/17 passed` and the NFR-2 result.
- Commands: backend `cd backend && ./mvnw -B verify`; loadtest `./mvnw -B -pl loadtest verify` (repo root; compiles simulations, runs unit tests, never runs Gatling); chart `deploy/helm/scripts/validate.sh`.

## Review Focus

1. **Missing or inline secret** — `helm template` without `secrets.existingSecret`, or with a `config` value containing a secret-looking inline value under a `*-ref` key that is not `env:NAME`, fails rendering with a message naming the value, instead of a pod crash-looping. Test in Task 3.
2. **OpenShift rejects pods with fixed UIDs** — rendering with `values-openshift.yaml` must contain no `runAsUser`, `runAsGroup`, `fsGroup` in any object (restricted-v2 SCC). Test in Task 4.
3. **Rolling update of a worker mid-dispatch** — `SIGTERM` while webhook calls are in flight: no new claims, every in-flight row gets its outcome written, no ERROR log, no re-send after the lease. Tests in Task 2 (IT) and Task 5 (kind smoke: `kubectl rollout restart` of the worker during traffic).
4. **Config change via `helm upgrade` not applied** — a changed `config` value changes the pod template annotation `checksum/config`, so pods roll. Test in Task 3.
5. **Load generator measuring itself** — Gatling CPU saturation or host port forwarding (Colima/Docker Desktop) capping throughput: Gatling runs inside the compose network and `load.sh` records its CPU, marking saturated runs `INVALID`. Test in Task 7.

---

### Task 0: Prerequisites (needs the maintainer's OK before installing anything)

- [x] **Step 1:** Ask the maintainer to approve installing `kind` and `kubectl` with Homebrew (`brew install kind kubectl`); they are used only by `scripts/helm-smoke.sh`. Helm, helm-unittest and kubeconform run from container images, Gatling from Maven: nothing else is installed.
- [x] **Step 2:** Pick and pin versions (latest release ≥ 2 weeks old, digest included) in `deploy/helm/tools.env`: `alpine/helm`, `helmunittest/helm-unittest`, `ghcr.io/yannh/kubeconform`, `kindest/node` (a Kubernetes 1.30+ node matching the installed kind release). Record the Gatling and `gatling-maven-plugin` versions for Task 6.
- [x] **Step 3:** Verify: `kind version`, `kubectl version --client`, `docker run --rm "$HELM_IMAGE" version` print the pinned versions. Colima has at least 4 CPUs / 6 GiB (`colima list`). The load stack caps API 2 + PostgreSQL 1 + Gatling 1 CPUs; if Task 8 runs come out `INVALID` or show CPU contention, ask the maintainer to restart Colima with 6 CPUs / 8 GiB (`colima stop && colima start --cpu 6 --memory 8`) — never restart it without that OK.
- [x] **Step 4:** Commit `build: pin helm, kubeconform and kind images` (only `deploy/helm/tools.env`).

### Task 1: Backend — management port

**Files:**
- Modify: `backend/src/main/resources/application.yml` (comment only: `management.server.port` is opt-in through `MANAGEMENT_SERVER_PORT`)
- Test: `backend/src/test/java/io/reliablepush/shared/ManagementPortIT.java`

**Interfaces:**
- Produces: with `MANAGEMENT_SERVER_PORT=8081`, `/actuator/health/{liveness,readiness}` and `/actuator/prometheus` are served on 8081 only; 8080 answers 404 for `/actuator/**`. Without it, behaviour is unchanged (same port).

- [x] **Step 1: Failing tests** (`@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "management.server.port=0")`, `@LocalManagementPort`): `readinessOnManagementPortOnly()` (200 on management port, 404 on server port); `prometheusOnManagementPort()` (body contains `rp_notifications_registered_total`); `managementRequestsDoNotEmitApiRequestEvent()` (no `event.action=api.request` line for a probe, captured with the existing log-capture test support); `managementRequestsAreNotRateLimited()` (more probe calls than the sender limit, all 200); `apiStillServedOnServerPort()` (`POST /v1/notifications` → 201).
- [x] **Step 2: Run** the IT (one-IT command from `AGENTS.md`) → FAIL where the behaviour is missing (tests that already pass stay as regression guards; note which in the report).
- [x] **Step 3: Implement** the smallest change that makes them pass (expected: configuration only; servlet filters live in the main context and do not apply to the management child context — confirm, do not assume).
- [x] **Step 4: Run** `cd backend && ./mvnw -B verify` → PASS. Update `docs/operations.md` "Health and metrics": in Kubernetes set `MANAGEMENT_SERVER_PORT=8081` (the chart does), probes and scrapes target it, probe calls no longer produce `api.request` lines.
- [x] **Step 5: Commit** `feat(backend): opt-in management port for probes and metrics`

### Task 2: Backend — graceful worker stop

**Files:**
- Create: `backend/src/main/java/io/reliablepush/fallback/FallbackWorkerLifecycle.java`
- Modify: `backend/src/main/java/io/reliablepush/fallback/FallbackScheduler.java` (stop flag), the fallback configuration class that creates `FallbackScheduler`, `backend/src/main/resources/application.yml` (`server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 30s`, explicit), `docs/operations.md`
- Test: `backend/src/test/java/io/reliablepush/fallback/FallbackSchedulerTest.java` (extend), `backend/src/test/java/io/reliablepush/fallback/WorkerGracefulShutdownIT.java`

**Interfaces:**
- Produces: `FallbackScheduler.requestStop()` — after it, `tick()` claims nothing more (checked before every claim, including the first) and returns once its rows in flight are done. `final class FallbackWorkerLifecycle implements SmartLifecycle` (worker profile only): `stop()` calls `requestStop()` and waits for a running tick to return for at most `reliable-push.worker.shutdown-timeout` (new key, default = the largest app `fallback.http-timeout` + 5 s; validated positive at startup); phase `Integer.MAX_VALUE - 100` so it stops before the web server and long before the DataSource is destroyed. A tick that outlives the timeout is logged at WARN once (`worker stop timed out; N rows may be re-sent after their lease`), no catalogue event.

- [x] **Step 1: Failing tests** — unit: `noClaimAfterStopRequested()` (stub repository records calls; `requestStop()` then `tick()` → zero `claimDue` calls); `stopDuringTickFinishesRowsInFlight()` (dispatcher blocks on a latch; `requestStop()` mid-tick; tick returns only after the latch opens and makes no further claim). IT (`WorkerGracefulShutdownIT`, Testcontainers PostgreSQL + WireMock receiver with a 2 s fixed delay, own `SpringApplication` context so the test closes it): `inFlightRowsCompleteOnContextClose()` — register a due notification, wait until its row is `DISPATCHING`, `context.close()`; after close the row is `FALLBACK_TRIGGERED` with `closed_at` set, WireMock saw exactly one call, no ERROR line was logged; `noClaimAfterClose()` — a row that falls due after `close()` began stays `PENDING`.
- [x] **Step 2: Run** → FAIL.
- [x] **Step 3: Implement.** Wait with a lock/condition owned by the scheduler (`awaitIdle(Duration)`), not by polling.
- [x] **Step 4: Run** `cd backend && ./mvnw -B verify` → PASS. Docs: `docs/operations.md` "Scaling and capacity" — on `SIGTERM` a worker stops claiming and finishes calls in flight (at most `shutdown-timeout`); set `terminationGracePeriodSeconds` above `shutdown-timeout + 30 s` (the chart default is 60); configuration reference gains `worker.shutdown-timeout`.
- [x] **Step 5: Commit** `feat(backend): graceful worker stop on shutdown`

### Task 3: Helm chart — workloads, configuration, security

**Files:**
- Create: `deploy/helm/reliable-push/{Chart.yaml,values.yaml,values.schema.json,.helmignore,README.md}`, `templates/{_helpers.tpl,configmap.yaml,deployment.yaml,service.yaml,serviceaccount.yaml,pdb.yaml,NOTES.txt}`, `deploy/helm/scripts/validate.sh`
- Test: `deploy/helm/reliable-push/tests/{deployment_test.yaml,configmap_test.yaml,service_test.yaml,pdb_test.yaml,required_test.yaml}` (helm-unittest)

**Interfaces:**
- Consumes: management port (Task 1), `worker.shutdown-timeout` (Task 2), container contract (`docs/operations.md` "Container image").
- Produces (values keys later tasks and docs use):
  - `mode: combined | split` (default `combined`). `combined` → one Deployment `<fullname>` with `SPRING_PROFILES_ACTIVE=api,worker,json`. `split` → `<fullname>-api` (`api,json`) and `<fullname>-worker` (`worker,json`).
  - `api.{replicas: 2, resources, podDisruptionBudget.minAvailable: 1, hikariMaximumPoolSize: 10}`, `worker.{replicas: 2, resources, strategy: RollingUpdate|Recreate, maxConcurrency: 100, hikariMaximumPoolSize: 10}` (defaults revised by Task 8; in `combined` mode `api.*` applies).
  - `image.{repository (required), tag (default .Chart.AppVersion), pullPolicy: IfNotPresent, digest}`; `imagePullSecrets`.
  - `database.{url (required), username (required)}`; the password is `SPRING_DATASOURCE_PASSWORD` in the existing Secret.
  - `secrets.existingSecret` (required): every `env:NAME` reference in `config` must be a key of this Secret (documented; not checkable at render time).
  - `config` (map, rendered verbatim under `reliable-push:` in ConfigMap `<fullname>-config` key `application.yml`, mounted at `/config`, `SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/config/`); `config.apps` must have at least one entry.
  - `podSecurityContext` (default `{runAsNonRoot: true, seccompProfile: {type: RuntimeDefault}}`), `securityContext` (container: read-only root, no privilege escalation, drop ALL), `javaToolOptions` (default `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`), `terminationGracePeriodSeconds: 60`, `extraEnv`, `extraVolumes`, `extraVolumeMounts`, `extraContainers`, `extraInitContainers`, `nodeSelector`, `tolerations`, `affinity`, `topologySpreadConstraints`, `podAnnotations`, `serviceAccount.{create: true, name, annotations}`.
  - Every pod template: env `MANAGEMENT_SERVER_PORT=8081`, `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`, worker `RELIABLEPUSH_WORKER_MAXCONCURRENCY`; annotation `checksum/config` = sha256 of the rendered ConfigMap; `emptyDir` `/tmp` (`sizeLimit: 64Mi`); probes on port `management`: startup `/actuator/health/liveness` (period 5 s, failureThreshold 36 = 3 min for Flyway), liveness same path, readiness `/actuator/health/readiness`; API pods `preStop` `exec: ["sleep", "5"]` (busybox in the image) so endpoints drain before Tomcat stops.
  - Services: `<fullname>-api` (ClusterIP, port 80 → `http`), `<fullname>-management` (ClusterIP, port 8081, selects all pods of the release, for scraping). Worker pods are never behind the API Service.
- [x] **Step 1: Failing tests** (helm-unittest suites): combined renders one Deployment with profiles `api,worker,json`; split renders two with the right profiles and only the API selected by `<fullname>-api`; security contexts as in Global Constraints; probes target `management`; `checksum/config` changes when `config.apps.shop.default-timeout-minutes` changes (Review Focus 4); `required_test.yaml`: missing `image.repository`, `database.url`, `secrets.existingSecret` or empty `config.apps` each fail with a message naming the key; a `*-ref` value under `config` that does not match `^env:[A-Za-z_][A-Za-z0-9_]*$` fails naming the key path (Review Focus 1); PDB rendered only when replicas > 1; ConfigMap content equals `reliable-push:` + `config` YAML.
- [x] **Step 2: Run** `deploy/helm/scripts/validate.sh` → FAIL. The script: sources `tools.env`; runs `helm lint --strict` and `helm unittest` from the pinned images with the chart mounted read-only; exits non-zero on any failure; Bash 3.2 compatible.
- [x] **Step 3: Implement** templates and `values.schema.json` (types and enums for `mode`, `worker.strategy`, required keys). `values.yaml` is a working example except for the required keys (placeholders commented, never secrets).
- [x] **Step 4: Run** `deploy/helm/scripts/validate.sh` → PASS.
- [x] **Step 5: Commit** `feat(helm): backend chart with combined and split modes`

### Task 4: Helm chart — exposure, monitoring, platform values

**Files:**
- Create: `templates/{ingress.yaml,route.yaml,hpa.yaml,servicemonitor.yaml,podmonitoring.yaml,tests/test-readiness.yaml}`, `deploy/helm/reliable-push/values-gke.yaml`, `deploy/helm/reliable-push/values-openshift.yaml`, `deploy/helm/reliable-push/ci/{default-values.yaml,split-values.yaml}` (test inputs with dummy required values)
- Modify: `deploy/helm/scripts/validate.sh` (kubeconform step)
- Test: `tests/{ingress_test.yaml,route_test.yaml,hpa_test.yaml,monitoring_test.yaml,platform_test.yaml}`

**Interfaces:**
- Produces: `ingress.{enabled: false, className, annotations, hosts[{host, paths: [/v1]}], tls}` → only prefix `/v1` routed to `<fullname>-api:http`; `route.{enabled: false, host, tls: {termination: edge, insecureEdgeTerminationPolicy: Redirect}}` (OpenShift `route.openshift.io/v1`, path `/v1`); `autoscaling.api.{enabled: false, minReplicas: 2, maxReplicas: 10, targetCPUUtilizationPercentage: 70}` (API only — worker scaling follows the backlog, not CPU); `metrics.serviceMonitor.{enabled: false, interval: 30s}` (`monitoring.coreos.com/v1`, port `management`, path `/actuator/prometheus`); `metrics.podMonitoring.{enabled: false}` (GKE Managed Prometheus `monitoring.googleapis.com/v1`); `helm test` pod (`busybox` pinned by digest in values, `wget -qO- http://<fullname>-management:8081/actuator/health/readiness`, runs non-root with the same security context).
  - `values-gke.yaml`: Ingress enabled (class `gce`, managed certificate annotation example commented), `podMonitoring.enabled: true`, `podSecurityContext.runAsUser: 1001`, `runAsGroup: 0`, commented Cloud SQL Auth Proxy native sidecar example via `extraInitContainers` (`restartPolicy: Always`).
  - `values-openshift.yaml`: Route enabled, `serviceMonitor.enabled: true` (user workload monitoring), no UID/GID/fsGroup.
- [x] **Step 1: Failing tests:** Ingress and Route never expose `/actuator` or port 8081; HPA only for the API Deployment (in `combined` mode: the combined Deployment, documented); `platform_test.yaml` renders with `values-openshift.yaml` and asserts no `runAsUser`/`runAsGroup`/`fsGroup` in any document (Review Focus 2), and with `values-gke.yaml` asserts `runAsUser: 1001`; monitoring objects only when enabled.
- [x] **Step 2: Run** `validate.sh` → FAIL. Add to it: `helm template` for `ci/default-values.yaml`, `ci/split-values.yaml`, `values-gke.yaml`+`ci/default-values.yaml`, `values-openshift.yaml`+`ci/split-values.yaml`, each piped to kubeconform `-strict -summary -kubernetes-version 1.30.0` with the default schemas plus the CRD catalog for `Route`, `ServiceMonitor`, `PodMonitoring`.
- [x] **Step 3: Implement.** **Step 4: Run** `validate.sh` → PASS (lint, unit tests, 4 × kubeconform valid).
- [x] **Step 5: Commit** `feat(helm): ingress, route, autoscaling, monitoring and platform values`

### Task 5: Local Helm smoke install on kind

**Files:**
- Create: `scripts/helm-smoke.sh`, `scripts/helm-smoke/{postgres.yaml,smoke-values.yaml}`

**Interfaces:**
- Consumes: chart (Tasks 3-4), `harness/compose/GenSecrets.java` (secrets), `deploy/helm/tools.env` (`KIND_NODE_IMAGE`).
- Produces: `./scripts/helm-smoke.sh [--keep]` — local only; exit 0 = passed; output in `harness/build/helm-smoke/` (pod logs, `helm status`).
- [x] **Step 1: Write the script:** create kind cluster `rp-smoke` from `KIND_NODE_IMAGE` (fail fast if it exists, unless `--keep` was used before: reuse); build `reliable-push-backend:smoke` and `kind load` it; apply `postgres.yaml` (Postgres 16 Deployment + Service, password from the generated secret, `emptyDir` storage); generate secrets with GenSecrets into `harness/build/helm-smoke/` and create Secret `rp-secrets` from it (strip the single quotes around PEM values, which `kubectl create secret --from-env-file` would keep); `helm install` with `mode: split`, 1 API + 2 workers, `smoke-values.yaml` (app `shop` signed-grant, fallback URL to a cluster-internal dummy that answers 200 — `busybox httpd` pod — and OpenShift-like `runAsUser: 1000870000`, `runAsGroup: 0`, no `fsGroup`).
- [x] **Step 2: Checks** (each prints PASS/FAIL, script fails on the first FAIL): rollouts complete within 180 s; `helm test` passes; through `kubectl port-forward` to `<fullname>-api`: `POST /v1/notifications` (sender key from the generated secret) → 201 and `GET` → 200; `/actuator/health` on the API Service port → 404 (management port only); Review Focus 3: register 200 notifications with `timeoutMinutes: 1`, wait until they fall due, `kubectl rollout restart deploy/<fullname>-worker` while they dispatch; after rollout: all 200 are `FALLBACK_TRIGGERED` (list API), no pod log line with `"log.level":"ERROR"`, no `fallback.reclaimed` event.
- [x] **Step 3: Run** `./scripts/helm-smoke.sh` → exit 0; cluster deleted (unless `--keep`).
- [x] **Step 4: Commit** `test(helm): local kind smoke install`

### Task 6: Load-test module

**Files:**
- Create: `loadtest/pom.xml`, `loadtest/src/main/java/io/reliablepush/loadtest/{LoadConfig,GrantSigner,DeviceProvisioner,BurstDrainProbe}.java`, `loadtest/src/test/java/io/reliablepush/loadtest/{EventIngestSimulation,FallbackBurstSimulation}.java` (Gatling simulations live in test sources, as the Gatling Maven plugin expects), `loadtest/src/test/java/io/reliablepush/loadtest/{LoadConfigTest,GrantSignerTest,BurstDrainProbeTest}.java`, `loadtest/README.md`
- Modify: root `pom.xml` (module `loadtest`)

**Interfaces:**
- Produces:
  - `record LoadConfig(URI apiUrl, String senderApiKey, String grantSecret, String grantIssuer, String appId, int devices, int notificationsPerSecond, Duration ramp, Duration steady, int burstSize, int burstPerSecond)`; `static LoadConfig fromEnv(Map<String,String> env)` reading `LOAD_API_URL` (default `http://api:8080`), `HARNESS_APPS_SHOP_SENDERAPIKEY`, `HARNESS_APPS_SHOP_GRANTSECRET`, `HARNESS_APPS_SHOP_GRANTISSUER` (from the generated secrets file), `LOAD_APP_ID` (`shop`), `LOAD_DEVICES` (2000), `LOAD_NOTIFICATIONS_PER_SECOND` (500), `LOAD_RAMP` (60s), `LOAD_STEADY` (300s), `LOAD_BURST_SIZE` (30000), `LOAD_BURST_PER_SECOND` (1500); missing secret → `IllegalStateException` naming the variable, never its value; `toString()` omits secrets.
  - `final class GrantSigner` — `String sign(String userId, Instant now)`: HS256 JWT, claims `iss`, `aud` = appId, `sub`, `iat`, `exp = iat + 120` (same shape as `harness/.../GrantMinter`), JDK `Mac` only.
  - `final class DeviceProvisioner` — `List<Map<String,Object>> provision(LoadConfig c)`: registers `devices` devices (`userId` = `load-user-<n>`, `platform` alternating `ANDROID`/`IOS`) through `POST /v1/devices` with JDK `HttpClient` on virtual threads (at most 50 in flight); returns feeder rows `{userId, deviceToken}`; any non-201 aborts with status and index (no token in the message).
  - `EventIngestSimulation`: `before()` provisions; one scenario per arrival: feed a device (circular), `POST /v1/notifications` (`policy NOT_READ`, `timeoutMinutes 10`, `fallback {"sms":"load"}`) save `trackingId`, pause 50 ms, `POST /v1/events` DELIVERED, pause 100 ms, `POST /v1/events` READ. Request names `register`, `event-delivered`, `event-read` (group `events` = both events). Injection: `rampUsersPerSec(50).to(nps).during(ramp)`, then `constantUsersPerSec(nps).during(steady)`. Assertions = NFR-2 criterion (Global Constraints) on groups `register` and `events`, `global().failedRequests().percent().lte(0.1)`.
  - `FallbackBurstSimulation`: `constantUsersPerSec(burstPerSecond).during(burstSize / burstPerSecond seconds)` of `POST /v1/notifications` (`policy NOT_DELIVERED`, `timeoutMinutes 1`, unique `userId` per request); assertions failed ≤ 0.1 %, p99 ≤ 250 ms.
  - `final class BurstDrainProbe` — `main(String[] args)` args `--metrics <url>... --expected <n> --timeout <duration> --out <file>`: polls every 1 s the Prometheus endpoints of all worker replicas, sums `rp_fallback_total{outcome="triggered"}`, `failed`, `expired` (each as a delta from the probe's first sample, taken **before** the burst starts) and `rp_due_backlog`; finishes when the triggered delta reaches `expected` or times out; `drainSeconds` runs from the first increase of `triggered` to the last; writes JSON `{expected, triggered, failed, expired, drainSeconds, rowsPerSecondOverall, rowsPerSecondPeak10s, maxBacklog, status: PASSED|FAILED}`; FAILED on timeout or any `failed`/`expired` delta > 0. `static Map<String, Double> parse(String prometheusText)` for the three series (exposed for tests).
  - pom: parent none, `groupId io.reliablepush`, `artifactId reliable-push-loadtest`, Java 21; Gatling deps test scope only; `gatling-maven-plugin` bound to **no** phase (run explicitly: `gatling:test -Dgatling.simulationClass=…`), so `verify` compiles simulations and runs unit tests only.
- [ ] **Step 1: Failing tests:** `LoadConfigTest` (defaults; missing `HARNESS_APPS_SHOP_SENDERAPIKEY` → message names the variable, not a value; `toString()` has no secret); `GrantSignerTest` (verifies the signature with an independent JDK `Mac` computation, claim set and `exp - iat == 120`, base64url without padding); `BurstDrainProbeTest` (parses a real Prometheus text sample incl. comments, labels in any order and `1.0E4` notation; a scripted sequence of samples → correct `drainSeconds`, peak rate and FAILED when `expired` increases).
- [ ] **Step 2: Run** `./mvnw -B -pl loadtest verify` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS (simulations compile; Gatling does not run).
- [ ] **Step 5: Commit** `test(load): gatling simulations, device provisioner and drain probe`

### Task 7: Load stack and `scripts/load.sh`

**Files:**
- Create: `loadtest/compose/load.override.yml`, `loadtest/Dockerfile`, `scripts/load.sh`
- Modify: `scripts/lib/common.sh` (only if a helper must be generalised; keep E2E/demo behaviour identical)

**Interfaces:**
- Produces: `./scripts/load.sh [--simulation ingest|burst|all] [--api-pool N] [--worker-concurrency N] [--worker-pool N] [--workers 1|2] [--keep]`; defaults `all`, 10, 100, 10, 1. Outputs in `harness/build/load/`: Gatling HTML report per simulation in `gatling/`, `burst.json` (probe), `summary.json` = array of `{simulation, status: PASSED|FAILED|INVALID, metrics, config: {apiCpus, apiMemory, apiPool, workers, workerConcurrency, workerPool}, generatorCpuAvgPercent}`. Exit 0 only if every simulation PASSED.
  - Override: project `reliable-push-load`, env files from `../build/load/`; `api`: `cpus: "2"`, `mem_limit: 1g`, `SPRING_PROFILES_ACTIVE=api,json`, `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=${LOAD_API_POOL:-10}`, `RELIABLEPUSH_APPS_SHOP_RATELIMITS_SENDERPERMINUTE=600000`; workers: `worker,json`, lease `2m`, `RELIABLEPUSH_WORKER_MAXCONCURRENCY`, pool; `postgres`: `cpus: "1"`, `command` with `max_connections=200`; `gatling` service (profile `load`, built from `loadtest/Dockerfile`, Temurin 21 JDK + root Maven wrapper, named volume for `~/.m2`, `cpus: "1"`) on the compose network, `env_file` = generated secrets, target `http://api:8080`. `RP_LOG_FILE` unset (stdout only; no log file growth during load).
- [ ] **Step 1: Write** override, Dockerfile and script. The script reuses `scripts/lib/common.sh` (tools check, busy-port fail-fast, readiness wait with the 120 s limit), generates secrets with the `secrets` service, removes `RELIABLEPUSH_TESTING_MINUTEDURATION` from `harness/build/load/secrets.env` (as `demo.sh --realtime`), stops `worker-b` when `--workers 1`, samples `docker stats --no-stream` of the `gatling` container every 5 s during a run (average → `generatorCpuAvgPercent`, > 90 → `INVALID`, Review Focus 5), starts `BurstDrainProbe` in the background inside the `gatling` container **before** the burst simulation (baseline sample), against `http://worker-a:8081/actuator/prometheus` (and `worker-b` if running; set `MANAGEMENT_SERVER_PORT=8081` on the workers), timeout 20 min (the trigger window); tears down with `down -v` unless `--keep`.
- [ ] **Step 2: Self-check** `./scripts/load.sh --simulation burst` with `LOAD_BURST_SIZE=2000` → completes, `summary.json` well-formed, exit 0; with the E2E stack running → fails fast with the busy-port message.
- [ ] **Step 3: Commit** `test(load): local load stack and load.sh`

### Task 8: Load runs, feedback loop and capacity sizing

**Files:**
- Modify: `docs/operations.md` (new subsection "Measured capacity" under "Scaling and capacity"), `deploy/helm/reliable-push/values.yaml` (pool/concurrency/resources defaults), `docs/STATUS.md`; backend files only if a fix is needed (each with its own failing test)

- [ ] **Step 1: Baseline** `./scripts/load.sh --simulation all` (defaults). Record `summary.json` numbers in the ledger.
- [ ] **Step 2: Feedback loop** (only if `ingest` FAILED or `burst` drained slower than 400 rows/s): at most 3 rounds; each round = one hypothesis from evidence (Gatling report, `docker stats`, a JFR recording via `JAVA_TOOL_OPTIONS=... -XX:StartFlightRecording=duration=120s,filename=/tmp/rp.jfr` copied out with `docker compose cp`), one change with its test (`superpowers:systematic-debugging`), full backend `verify`, re-run. Configuration changes count as rounds. After round 3 without a pass: stop, record the measured ceiling and the evidence in `docs/STATUS.md` and ask the maintainer whether to change NFR-2 or the replica size (spec change), do not lower the criterion silently.
- [ ] **Step 3: Sizing matrix** (each run on the passing build): `ingest` with `--api-pool 5`, `10`, `20`; `burst` with `--worker-concurrency 50/100/200` × `--worker-pool 10/20`, `--workers 1`, plus one `--workers 2` run at the best single-worker setting. Table per run: p95/p99/failed % (ingest), rows/s overall and peak, max backlog (burst), generator CPU; machine (`sysctl hw.model`, Colima CPUs/memory) noted, with the caveat that numbers are relative to it.
- [ ] **Step 4: Apply:** chart defaults (`api.hikariMaximumPoolSize`, `worker.maxConcurrency`, `worker.hikariMaximumPoolSize`, API/worker `resources.requests` = measured CPU at target + 30 %, memory limit from observed RSS + 30 %), and the formula examples in `docs/operations.md` corrected where the measurements disagree; `validate.sh` still PASS.
- [ ] **Step 5: Commit** `docs(ops): measured capacity and chart sizing defaults` (plus any `fix(backend): …` commits from Step 2).

### Task 9: CI, documentation and handoff

**Files:**
- Create: `.github/workflows/helm-ci.yml`, `docs/deployment.md`
- Modify: `.github/workflows/harness-ci.yml` (paths `loadtest/**`; job `loadtest`: `./mvnw -B -pl loadtest verify`), `.github/dependabot.yml` (docker `/loadtest`; maven `/` already covers the module), `README.md` (docs table: deployment; components table: `deploy/helm/`, `loadtest/`), `AGENTS.md` (Components and Commands: `deploy/helm/scripts/validate.sh`, `./scripts/helm-smoke.sh`, `./scripts/load.sh`), `docs/testing.md` (load tests and Helm checks sections), `docs/specs/2026-11-03-reliable-push-design.md` ("Changes during implementation (plan 1C)": management port, graceful worker stop + `worker.shutdown-timeout`, chart layout and values, `loadtest/` in §12, NFR-2 criterion and result), `docs/requirements.md` (NFR-2: validated value and link to the measurement), `docs/STATUS.md`
- [ ] **Step 1: Workflow** `helm-ci.yml`: triggers like `harness-ci.yml` with paths `deploy/helm/**` and the workflow file; one job running `deploy/helm/scripts/validate.sh` (images from `tools.env`); actions pinned to the same SHAs as the other workflows; `permissions: contents: read`; no cluster, no load job.
- [ ] **Step 2: Validate** `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12` → no errors.
- [ ] **Step 3: `docs/deployment.md`:** prerequisites (PostgreSQL 16 — Cloud SQL / OpenShift operator, `max_connections` vs pools), creating the Secret (key names, `kubectl create secret generic … --from-file` for PEMs), installing on GKE (`-f values-gke.yaml`, Ingress, managed certificates, Managed Prometheus, Cloud SQL proxy sidecar) and on OpenShift (`-f values-openshift.yaml`, Route, SCC note, user workload monitoring), combined vs split, upgrades (V3 `Recreate` note, `checksum/config`), graceful shutdown, sizing pointer to `docs/operations.md`, per-IP limits at the ingress. Every command runs as written against the kind smoke cluster where possible (`--keep`).
- [ ] **Step 4: Final verification:** `cd backend && ./mvnw -B verify`, `./mvnw -B -pl loadtest verify`, `./mvnw -B -pl harness -am verify`, `deploy/helm/scripts/validate.sh`, `./scripts/helm-smoke.sh`, `./scripts/e2e.sh` (17/17, the backend changed), `./scripts/load.sh --simulation all` → all green; record counts, the E2E line and the NFR-2 line in `docs/STATUS.md`.
- [ ] **Step 5: Commit** `ci: helm checks and loadtest build; docs: deployment and load testing`; then run the `session-handoff` skill.
