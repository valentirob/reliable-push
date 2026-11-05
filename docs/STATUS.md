# Status / handoff log

Newest entry first. Updated at the end of every work session (see `.agents/skills/session-handoff/SKILL.md`).

## 2026-11-05 (7) — Claude Code — `feature/cycle2-android-sdk` (plan 2, final fix wave)

- **Done:** R18 fix wave (whole-branch review minors): SDK no longer posts or reports `DELIVERED` for a blocked channel
  group or paused notifications; `register`/`logout` always call back (`Exception`), `TokenStore.save` never throws;
  `Retry-After`/latency clamped to 1 h without overflow; `Sdk.flush` no longer reschedules a job `persist` just
  scheduled; docs retry wording; harness validates the FCM `project_id`, maps any `RuntimeException` in `FcmSender.send`
  to `FAILED(0)`, clears an FCM token only if unchanged (compare-and-clear); `AndroidE2eSummary` fails an expected
  scenario that was SKIPPED (except `fcm-real-delivery`); `android-e2e.sh` usage, early skip of `--test
  fcm-real-delivery`, separate `publishToMavenLocal` call, airplane mode restored to the saved state.
- **State (not merged, not pushed):** `./gradlew build` OK (release AAR 67,347 bytes, gate 102,400); `./mvnw -B -pl
  harness -am verify` OK; `./scripts/e2e.sh`: `E2E: 17/17 passed`; `./scripts/android-e2e.sh` and `--release-lib`:
  `Android E2E: 8/8 passed, 1 skipped` (each after one rerun, see below); `--test fcm-real-delivery` skips in under 1 s.
  Two single-entry failures were environmental and passed on rerun: `token-refresh-near-expiry` (the host stalled 15 min
  inside the test, so the 20 s token had expired: refresh answered 401 by design) and, with `--release-lib`,
  `offline-delivery-is-sent-when-network-returns` (JobScheduler did not start the job for 85 s after its latency, state
  `NET READY`; the OS delay alarm is inexact). No emulator or stack left running.
- **Follow-ups (not done):** `ReliablePushOptions` builder before 1.0; publish the R8 `mapping.txt`; move test hooks out
  of the public `ReliablePush` object.

## 2026-11-05 (6) — Claude Code — `feature/cycle2-android-sdk` (plan 2, Android SDK, Task 8 and handoff)

- **Done:** all plan 2 tasks (0 to 8) are implemented and ticked: the Android SDK (`android/reliablepush`, Kotlin, Kotlin
  stdlib only), the sample app, emulator E2E (`./scripts/android-e2e.sh`), the harness grants / real-devices / Host
  allowlist (closes the earlier open item "console write guard / Host allowlist against DNS rebinding") and the optional
  real FCM sender. Task 8: `.github/workflows/android-ci.yml` (`./gradlew build`, AAR size gate < 102,400 bytes, Gradle
  wrapper validation, test reports on failure; no emulator, no credentials), a CodeQL Android job, Dependabot `gradle` for
  `/android`, `maven-publish` + `signing` (only when `SIGNING_KEY`/`SIGNING_PASSWORD` are set; `publishToMavenLocal` runs,
  the POM lists only `kotlin-stdlib`), `docs/android.md`, integration guide §4, README, `AGENTS.md`, `docs/testing.md`, the
  run-e2e skill and the spec block "Changes during implementation (plan 2)". actionlint 1.7.12 is clean.
- **AAR size:** the release AAR was 98,307 bytes, too close to the gate, so the library's release build is now minified
  with R8 (`reliablepush/proguard-rules.pro`: keeps the public API in `io.reliablepush.android` and `RetryJobService`,
  `-repackageclasses io.reliablepush.android.internal` so internal classes cannot collide with other minified libraries,
  and no `-allowaccessmodification`, so no member is widened): **65,910 bytes**. `javap -public` of the 7 public and
  manifest classes is identical between the debug and release classes. The unit tests run on the debug variant (no
  `testReleaseUnitTest`); the minified code is exercised at runtime by `./scripts/android-e2e.sh --release-lib` (the
  sample's debug build consumes the release AAR from mavenLocal): `Android E2E: 8/8 passed, 1 skipped`. The library's
  own instrumented Keystore test in that run still runs against the debug library (internal class under test).
- **State (verification on this branch, not merged, not pushed):** `cd android && ./gradlew build` OK;
  `./mvnw -B -pl harness -am verify` OK (34 client + 135 harness unit + 79 harness integration tests, 0 failures);
  `cd backend && ./mvnw -B verify` OK (240 unit + 218 integration, 0 failures, coverage gate met);
  `./scripts/e2e.sh`: `E2E: 17/17 passed`; `./scripts/android-e2e.sh` and `./scripts/android-e2e.sh --release-lib`: each `Android E2E: 8/8 passed, 1 skipped`
  (`fcm-real-delivery` skipped). No emulator or stack left running.
- **Not run:** the real-FCM check (Task 7 Step 7): not run, it needs the maintainer's Firebase project
  (`android/sample/google-services.json` and `harness/build/fcm/service-account.json`, see `docs/testing.md`).
- **Next:** (1) final whole-branch review of `feature/cycle2-android-sdk` (from `648ee28`) and a fix wave; the deferred
  minors are in `.superpowers/sdd/2026-11-05-cycle2-android-sdk/progress.md` (Task 7 ones are worth considering).
  (2) The maintainer decides the merge into `develop` (`--no-ff`; merge line `E2E: 17/17 passed` and `Android E2E: 8/8
  passed, 1 skipped`). (3) Optionally run `fcm-real-delivery` with the Firebase files. (4) Then cycle 3 (iOS).
- **Open:** (a) Real FCM delivery unverified until the maintainer runs it. (b) Maven Central release not set up
  (namespace and signing key are the maintainer's). (c) The chart was never installed on a real GKE or OpenShift cluster;
  `scripts/*.ps1` untested on Windows. (d) The Android CI workflow has not run on GitHub yet (validated locally with
  actionlint only); the Gradle actions SHA is `v6.4.0`.

## 2026-11-05 (5) — Claude Code — `develop`

- **Done:** `feature/cycle1c-deploy-load` (with the plan commit) merged into `develop` (`--no-ff`, `e1be03f`) and pushed;
  local branches `feature/cycle1c-deploy-load` and `feature/plan-cycle1c` deleted. The final fix wave was re-reviewed
  clean before the merge. Plans 1A, 1B and 1C (cycle 1: backend, harness, deploy and load) are all on `develop`.
- **State:** the merged tree is identical to the branch head verified in entry (4) (`E2E: 17/17 passed`, NFR-2 passed with
  `nfr2Criterion: true`); on `develop` `./mvnw -B -pl harness,loadtest -am verify` re-run: 34 client + 106 harness unit +
  73 harness integration + 19 loadtest tests, 0 failures. GitHub Actions on `develop` (`e1be03f`): Backend CI ✅, Client
  and harness CI (incl. the new `loadtest` job) ✅, Helm chart CI (first run) ✅, CodeQL ✅. `main` is still the initial
  commit (no release yet).
- **Next:** cycle 2, the Android SDK (spec §7, §7.2): write its implementation plan (`docs/plans/`) from the approved
  spec, then implement. Needs a local Android SDK / emulator for the instrumented and E2E tests (spec §10.1). Then cycle 3
  (iOS).
- **Open:** (a) The chart was never installed on a real GKE or OpenShift cluster (the `BackendConfig` health check and
  its firewall note are unverified on GKE). (b) `scripts/*.ps1` untested on Windows. (c) ~~Console write guard: Host
  allowlist against DNS rebinding~~ — resolved in plan 2 (Task 5). (d) Small leftovers from the plan 1C reviews, left on purpose: two long doc lines
  (`docs/deployment.md`, spec), cosmetic nits in `scripts/load.sh`, and a narrow race in `scripts/helm-smoke.sh` (a
  same-named cluster created during `kind create` would be treated as owned).

## 2026-11-05 (4) — Claude Code — `feature/cycle1c-deploy-load` (plan 1C, Task 9 and final fix wave)

- **Done:** CI and docs for plan 1C; all plan 1C tasks are ticked. `.github/workflows/helm-ci.yml` (runs
  `deploy/helm/scripts/validate.sh`; no cluster, no load job), `harness-ci.yml` job `loadtest` (`./mvnw -B -pl loadtest
  verify`) and path `loadtest/**`, Dependabot docker `/loadtest`; actionlint 1.7.12 clean. New `docs/deployment.md`
  (Secret, values, install and checks, GKE, OpenShift, combined vs split, upgrades, graceful shutdown, sizing, per-IP
  limits); its generic commands were run against the kind smoke cluster (`--keep`), GKE/OpenShift only rendered. README,
  `AGENTS.md`, `docs/testing.md` (load tests, Helm checks), spec "Changes during implementation (plan 1C)", NFR-2 in
  `docs/requirements.md` marked validated.
- **Final fix wave** (after the whole-branch review): removed the `worker.shutdown-timeout` ≤
  `spring.lifecycle.timeout-per-shutdown-phase` startup check (R10: the worker stop is synchronous; grace rule
  `terminationGracePeriodSeconds > preStop 5 s + worker.shutdown-timeout + 30 s`); worker stop tests (graceful-shutdown
  IT with batch-size 1, tick counter in `FallbackScheduler`, `FallbackWorkerLifecycleTest`); chart
  `api.service.annotations` and `gke.backendConfig` (GKE health check on 8081 `/actuator/health/readiness`, on in
  `values-gke.yaml`); PDB follows `autoscaling.api.minReplicas` with the HPA, `*Ref`/`*_ref` keys checked like `-ref`;
  `load.sh` records the effective settings and `nfr2Criterion` in `summary.json`, burst warm-up (`LOAD_BURST_WARMUP`),
  ingest stops after a failed register; `helm-smoke.sh` marks a kept cluster by its control-plane container ID and wipes
  `logs/` on reuse.
- **State (verification after the final fix wave, on this branch, not merged, not pushed):** `cd backend && ./mvnw -B
  verify` 240 unit + 218 integration tests, 0 failures, line coverage 93.3 %; `./mvnw -B -pl loadtest verify` 19 tests;
  `./mvnw -B -pl harness -am verify` 34 client + 106 harness unit + 73 harness integration tests (Task 9, harness and
  client unchanged since); `deploy/helm/scripts/validate.sh` lint (both modes) + 83 helm-unittest tests + kubeconform on
  4 renders (the GKE one includes the `BackendConfig`), all passed; `./scripts/helm-smoke.sh --keep` then
  `./scripts/helm-smoke.sh` (reuse of the kept cluster) 12/12 PASS each (worker rollout during dispatch: 200/200
  triggered once, no ERROR); `./scripts/e2e.sh`: `E2E: 17/17 passed`; `./scripts/load.sh --simulation all`: **NFR-2:
  passed** (ingest PASSED with `nfr2Criterion: true`: register p95 1 ms / p99 28 ms, events p95 2 ms / p99 34 ms, 0 %
  failed, generator CPU 10.8 %; criterion and replica size unchanged, see `docs/operations.md` "Measured capacity");
  burst PASSED (30,000 triggered, 0 failed or expired, 1,553 rows/s); `./scripts/load.sh --simulation burst` on a fresh
  stack PASSED (`notify` p99 1 ms after the 30 s warm-up; 30,000 triggered, 0 failed or expired).
- **Next:**
  1. Review of the final fix wave (range from `17b774c`); the other deferred minors stay as triaged in the SDD ledger.
  2. Then the maintainer decides the merge into `develop` (`--no-ff`; merge line `E2E: 17/17 passed` and the NFR-2 result).
- **Open:** (a) The chart was never installed on a real GKE or OpenShift cluster (render and kubeconform only; the
  `BackendConfig` health check and its firewall note are unverified on GKE). (b) Earlier open items: `scripts/*.ps1`
  untested on Windows; console Host allowlist.

## 2026-11-05 (3) — Claude Code — `feature/cycle1c-deploy-load` (plan 1C, Task 8)

- **Done:** local load runs (`./scripts/load.sh`) and capacity sizing. **NFR-2 passed:** one API replica (2 CPUs / 1 GiB)
  took 1,000 events/s + 500 notifications/s for 5 minutes with 0 % failed, p95 1-2 ms, p99 28-38 ms (pool 5, 10 and 20).
  The default burst (30,000 rows at 1,500/s) drained at the arrival rate (about 1,550 rows/s, criterion ≥ 400); a backlog
  due at once drained at 2,900-4,900 rows/s per worker (3,100-3,400 at 1 CPU), capped by PostgreSQL's 1 CPU. The first
  baseline failed on the generator, not the backend (Gatling container out of ephemeral ports); fixed in the load
  override (one feedback round, no backend change). Results and chart defaults: `docs/operations.md` "Measured capacity".
- **State:** chart defaults revised (API requests 800m / 384Mi; worker max-concurrency 200, pool 20, requests 500m /
  384Mi, limits 1 CPU / 512Mi); `deploy/helm/scripts/validate.sh` passes. Machine: Mac17,2, Colima 4 CPUs / 6 GiB.
- **Next:** Task 9 of plan 1C (CI for chart and loadtest, final verification).
- **Open:** a burst-only run on a freshly started stack fails its p99 ≤ 250 ms assertion (cold JIT, p99 400-460 ms);
  `load.sh --simulation all` warms the API first.

## 2026-11-05 (2) — Claude Code — `develop`

- **Done:** `feature/cycle1b-harness` merged into `develop` (`--no-ff`, `fad89f9`) and pushed; branch deleted. Plans 1A and 1B are both on `develop`.
- **State:** on the merged tree: `./mvnw -B -pl harness -am verify` 34 client + 106 harness unit + 73 harness integration tests, 0 failures; `cd backend && ./mvnw -B verify` 229 unit + 212 integration tests, 0 failures. GitHub Actions on `develop` (`fad89f9`): Backend CI (incl. Trivy from its pinned image) ✅, Client and harness CI (first run) ✅, CodeQL ✅. `main` is still the initial commit (no release yet).
- **Next:** plan 1C: Helm chart for GKE/OpenShift (`deploy/helm/reliable-push`), Gatling load test, API/worker pool sizing. Then the Android SDK cycle, then iOS.
- **Open:** (a) `scripts/e2e.ps1` / `scripts/demo.ps1` untested on Windows. (b) Console write guard: harden with a Host allowlist (127.0.0.1/localhost/[::1], `/hooks/` exempt) against DNS rebinding and match on the normalized path. (c) Backend follow-ups from the 2026-11-04 entry (graceful worker shutdown, claim coalescing, client-abort logging, management port, stale JWKS cap, `api` profile gating).

## 2026-11-05 — Claude Code — `feature/cycle1b-harness`

- **Done:** final whole-branch review of plan 1B and its fix wave. The `console-smoke` flake was root-caused: a stale device-list response in the console's Send push screen could reset a chosen behaviour (e.g. `never` silently sent as `deliver:1s`); fixed with request-sequence guards in all console views and pinned by a deterministic Playwright regression test (held route) plus failure diagnostics. Also: inbox mode select no longer overwritten by the 3 s poll; console defaults to app `shop`; backend errors carry their real status (429 + Retry-After); cross-site guard on console writes (same-origin, JSON bodies); log-hygiene scans the signing private key and DB password; serialized log-tail polls.
- **State:** `./mvnw -B -pl harness -am verify`: 34 client + 106 harness unit + 73 harness integration tests, 0 failures. `./scripts/e2e.sh`: `[e2e] summary: 17/17 passed (expected 17 scenarios)`. Console smoke against `./scripts/demo.sh`: 5/5 runs passed. Not merged, not pushed (`develop` on GitHub lacks the latest local commits).
- **Next:** merge `feature/cycle1b-harness` into `develop` (`--no-ff`), push `develop`, check `backend-ci` and `harness-ci`; then plan 1C (Helm chart, Gatling load test, API/worker pool sizing) and the Android SDK cycle.
- **Open:** (a) `scripts/e2e.ps1` / `scripts/demo.ps1` untested on Windows. (b) Console write guard: harden with a Host allowlist (127.0.0.1/localhost/[::1], `/hooks/` exempt) against DNS rebinding and match on the normalized path. (c) `harness-ci.yml` not yet run on GitHub.

## 2026-11-04 (2) — Claude Code — `feature/cycle1b-harness`

- **Done:** plan 1B complete: zero-dependency Java sender client (`client-java/`), harness (`harness/`: signed-grant device simulator, fake sender, HMAC-verifying fallback receiver, scenario runner, six-screen web console with SSE), local E2E (`./scripts/e2e.sh`, 16 scenarios + `console-smoke`), demo (`./scripts/demo.sh [--realtime]`, console at http://127.0.0.1:8090/), CI workflow `harness-ci.yml` (jobs `client` and `harness`, never E2E), Dependabot entries (maven `/`, docker `/harness`) and `commit-message` prefixes on all entries (`build(deps)`, `ci(deps)`). Docs: `docs/testing.md`, `docs/integration-guide.md` (client usage), README, `AGENTS.md`, `CONTRIBUTING.md`, `.agents/skills/run-e2e/SKILL.md`; spec §10.1/§10.2 drift fixed and noted under "Changes during implementation (plan 1B)".
- **State:** `./mvnw -B -pl client-java verify`: 34 tests, 0 failures (enforcer: no runtime dependencies). `./mvnw -B -pl harness -am verify`: harness 100 unit + 68 integration tests, 0 failures, coverage gate met. `./scripts/e2e.sh`, exit 0: `[e2e] summary: 17/17 passed (expected 17 scenarios)` (2026-11-04). `./scripts/demo.sh` and `-Pconsole-smoke` against the demo stack run as documented. actionlint 1.7.12 clean. Not merged, not pushed.
- **Next:**
  1. Final whole-branch review of `feature/cycle1b-harness`, then merge `feature/cycle1a-backend` and `feature/cycle1b-harness` to `develop` (`--no-ff`); report `E2E: 17/17 passed` in the merge commit.
  2. `gh auth login`, push `main`, `develop`; check the first CI runs (`backend-ci`, `harness-ci`).
  3. Plan 1C: Helm chart, Gatling load test, API/worker pool sizing. Then the Android SDK cycle.
- **Open:** (a) `scripts/e2e.ps1` and `scripts/demo.ps1` (Windows) are untested; PowerShell commands in the docs were not run. (b) Watch for a `console-smoke` first-run flake: once, a smoke run against a freshly started demo stack timed out waiting for the fallback row; two reruns passed and the E2E run passed. (c) `harness-ci.yml` has not run on GitHub yet (actionlint only); the client job builds with JDK 21 and `--release 17`.

## 2026-11-04 — Claude Code — `feature/cycle1a-backend`

- **Done:** whole-branch review of plan 1A (no critical findings) and its fix wave: `TrackingServiceTest` → `TrackingServiceIT`; device tokens of an app no longer configured → generic 401 (`unknown_app`); bare numbers mean minutes for `backoff`/`lease` and days for `device-token-validity`; startup fails when no app is configured; worker drains the due backlog within one tick (bounded by lease/2) with `worker.max-concurrency`; Hikari connection timeout 5 s and pool timeouts answered 503 + `Retry-After: 5`; cancel of an already-closed notification → 409; retention cron configurable and disabled in tests. Docs updated accordingly.
- **State:** `./mvnw -B verify` BUILD SUCCESS: 229 unit + 210 integration tests, 0 failures, line coverage 93.3%. `./mvnw -B verify -Pimage`: 4/4. Not merged to `develop`, not pushed.
- **Next:**
  1. Maintainer decides: merge `feature/cycle1a-backend` into `develop` (`--no-ff`).
  2. `gh auth login`, then push `main`, `develop` (and optionally the feature branch); check the first CI run.
  3. Plan 1B (client-java, harness, `scripts/e2e.sh`, web console), then plan 1C (Helm, Gatling, API/worker load and pool sizing).
- **Open (follow-ups from reviews):** graceful worker shutdown (stop flag on context close); claim coalescing under sustained backlog; client-abort IOException logged as ERROR; `/actuator/prometheus` on a management port (Helm); cap stale JWKS key use (~24 h); `api` profile gates nothing (log active roles at startup). (Dependabot commit-message prefixes: done in plan 1B.)

## 2026-11-03 (3) — Claude Code — `feature/cycle1a-backend`

- **Done:** plan 1A backend implemented (tasks 1-17: schema V1-V3, apps/config validation, sender API, device API with OIDC / signed-grant identity and ES256 device tokens, events, fallback worker with lease and trigger window, signed webhook client, retention, metrics, ECS JSON logging, container image, CI) and documented (task 18): `docs/architecture.md`, `docs/api.md`, `docs/integration-guide.md`, `docs/security.md`, `docs/operations.md`, `docs/testing.md`, README quick start, `AGENTS.md` commands. Spec and `.agents/rules/backend-java.md` updated with the decisions taken during implementation (see "Changes during implementation" at the top of the spec).
- **State:** `cd backend && ./mvnw -B verify` BUILD SUCCESS: 219 unit tests + 195 integration tests, 0 failures; JaCoCo line coverage 93.4% (gate 80%). `./mvnw -B verify -Pimage` BUILD SUCCESS: 4 image tests; image about 105 MB uncompressed (budget 250 MB). The README quick start was run against the built image (register, list, worker claim). Not pushed (no GitHub credentials here); no end-to-end, console or load tests yet (plan 1B/1C).
- **Next:**
  1. Whole-branch review of `feature/cycle1a-backend`, fix findings, merge to `develop` (`--no-ff`).
  2. Run `gh auth login`, then push `main`, `develop` and the feature branch; check the first CI run (Dependabot and the CodeQL schedule only start once the workflows are on the default branch).
  3. Plan 1B: `client-java`, harness (fake OIDC, fallback receiver, device simulator), `scripts/e2e.sh`, web console. Then plan 1C: Helm chart (`deploy/helm/reliable-push`) and Gatling load test.
- **Open:** (a) the `api` profile does not gate any bean: only `worker` does, HTTP endpoints are served by every instance (documented in `docs/operations.md` and the spec; decide whether to gate controllers/filters behind `api`). (b) macOS LibreSSL generates P-256 keys with explicit curve parameters by default, which the backend rejects: the docs use `-pkeyopt ec_param_enc:named_curve`. (c) GitHub push still blocked until `gh auth login`. (d) Do not run pre-V3 and current workers side by side (see `docs/operations.md`).

## 2026-11-03 (2) — Claude Code — `develop`

- **Done:** spec approved by maintainer (`docs/specs/2026-11-03-reliable-push-design.md`, revisions: trigger window, Log4j 2 ECS logging, dependency budget, Spring Boot 4.x, local-only E2E feedback loop, harness web console). Plan 1A written: `docs/plans/2026-11-03-cycle1a-backend.md` (18 tasks + Task 0 prerequisites).
- **State:** no product code yet. This Mac has only Homebrew OpenJDK 27, no JDK 21, no Maven, no Docker runtime → Task 0 needed before Task 2.
- **Next:**
  1. Maintainer reviews plan 1A and picks the execution method.
  2. Task 0 (install JDK 21, Maven, Colima/Docker), then Task 1.
  3. After 1A: write plan 1B (client-java, harness, e2e.sh, web console) and 1C (Helm, Gatling).
- **Open:** GitHub push still blocked until `gh auth login`.

## 2026-11-03 — Claude Code — `feature/agent-config`

- **Done:** repo initialised with gitflow (`main`, `develop`); LLM-agnostic agent setup: `AGENTS.md`, `CLAUDE.md`, `.agents/rules/`, `.agents/skills/`, `.claude/agents/`.
- **State:** no product code yet. Design agreed in conversation (sidecar integration, PostgreSQL-only, OIDC-pluggable device auth, per-notification fallback policy, multi-app single org, modular monolith). Spec not written yet.
- **Next:**
  1. Write the design spec to `docs/specs/2026-11-03-reliable-push-design.md` and `docs/requirements.md`; maintainer review.
  2. Write the implementation plan for cycle 1 (contracts + backend + harness + repo scaffolding: LICENSE, README, docs, CI).
- **Open:** GitHub push not possible from the original machine until `gh auth login` is done.
