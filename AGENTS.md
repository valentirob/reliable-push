# AGENTS.md — reliable-push

This is the canonical, LLM-agnostic context file for this repository. It is read
natively by Google Antigravity (and other tools that follow the AGENTS.md
convention). Claude Code reads it through `CLAUDE.md`. Keep tool-specific files
thin and put shared knowledge **here** or in the files linked below.

## Project in one paragraph

reliable-push is an MIT-licensed add-on for teams that already send push
notifications (FCM / APNs). It tracks whether each push was **delivered** and
**read** on the device and, if a per-notification policy is not satisfied
within N minutes, triggers a **fallback** by calling a configurable,
HMAC-signed webhook (`userId`, message, `trackingId`) that routes to SMS,
email or any other channel. It runs as a sidecar: the existing backend keeps
sending pushes itself.

## Components

| Path | What | Stack |
|---|---|---|
| `backend/` | Tracking + fallback service (modular monolith, `api`/`worker` profiles) | Java 21, Spring Boot 4, PostgreSQL, Flyway |
| `android/` | Android SDK (FCM v1 data mode + opt-in AndroidConfigV2 mode) | Kotlin, minSdk 26 |
| `ios/` | iOS SDK + Notification Service Extension helper | Swift, iOS 15+, SPM |
| `client-java/` | Optional zero-dependency Java client for the sender API | Java 17+, JDK only |
| `harness/` | Fake sender, fake fallback receiver, device simulator, E2E scenarios, web console for manual tests and demos (`./scripts/demo.sh`) | Java, plain HTML/JS, docker-compose |
| `deploy/helm/` | Helm chart for the backend only (`deploy/helm/reliable-push`, combined/split, GKE and OpenShift values); tool images pinned in `deploy/helm/tools.env` | Helm, Kubernetes ≥ 1.30 |
| `loadtest/` | Local-only Gatling load tests (NFR-2 ingest, fallback burst) and a JDK-only drain probe, run by `./scripts/load.sh` | Java 21, Gatling, docker-compose |
| `docs/` | Requirements, specs, plans, guides, status | Markdown |

## Where the knowledge lives (read before working)

1. `docs/requirements.md` — what the product must do (source of truth for scope).
2. `docs/specs/` — approved design specs. Do not contradict them; propose a spec change instead.
3. `docs/plans/` — implementation plans with checkbox tasks. Work the next unchecked task.
4. `docs/STATUS.md` — **handoff log**: current state, last session, next steps. Read first, update last.
5. `.agents/rules/*.md` — coding and process rules (listed below). Antigravity loads them automatically; other tools must read them explicitly.
6. `.agents/skills/*/SKILL.md` — repeatable workflows (Agent Skills open standard).

### Rules index

| Rule | Applies to |
|---|---|
| `.agents/rules/gitflow.md` | Always |
| `.agents/rules/handoff.md` | Always |
| `.agents/rules/security.md` | Auth, tokens, APIs, webhooks, secrets |
| `.agents/rules/backend-java.md` | `backend/**`, `harness/**` |
| `.agents/rules/mobile-sdk.md` | `android/**`, `ios/**` |

### Skills index

| Skill | Use it to |
|---|---|
| `implement-task` | Take the next plan task, implement it test-first, verify, commit |
| `review-loop` | Run independent review passes (correctness, security, tests) and fix until clean |
| `run-e2e` | Start the harness and run end-to-end scenarios |
| `session-handoff` | Update `docs/STATUS.md` and the plan before ending a session |

## Working agreement (any LLM, any human)

- Branching is **gitflow** (see rule). Never commit directly to `main`.
- Test first: no production code without a failing test that requires it.
- Never claim "done" without running the verification commands and reading their output.
- Keep files small and single-purpose; follow the existing package structure.
- English for code, comments, commits and docs.
- No secrets in the repo. Use `.env.example` / `application-example.yml` with placeholders.
- End every session with the `session-handoff` skill so another agent, on another machine and another LLM, can continue.

## Commands

Environment: JDK 21 and Docker (Testcontainers; the image tests build the container image). On a Mac with Homebrew OpenJDK 21
and Colima, export `JAVA_HOME=/opt/homebrew/opt/openjdk@21`, put its `bin` first on `PATH`, and set
`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` before
running the Maven commands. Details: `docs/testing.md`.

| Task | Command |
|---|---|
| Backend build + unit + integration tests + coverage gate (80% line) | `cd backend && ./mvnw -B verify` |
| Backend container image + image tests | `cd backend && ./mvnw -B verify -Pimage` |
| Backend unit tests only / one class | `cd backend && ./mvnw -B test` / `./mvnw -B test -Dtest=FallbackDecisionTest` |
| Backend one integration test | `cd backend && ./mvnw -B verify -Dit.test=NotificationApiIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` |
| Backend image build only | `docker build -t reliable-push-backend:dev backend` |
| Java sender client build + tests (run from the repo root) | `./mvnw -B -pl client-java verify` |
| Harness build + tests (includes `client-java`) | `./mvnw -B -pl harness -am verify` |
| Console smoke test against a running stack (needs Playwright's Chromium) | `./mvnw -B -pl harness -am verify -Pconsole-smoke` |
| Demo / manual-test web console (local only; `--realtime` for real minutes) | `./scripts/demo.sh [--realtime]` → http://127.0.0.1:8090/ |
| E2E (local only, manual or agent) | `./scripts/e2e.sh [--scenario <name>] [--keep]` (Windows: `pwsh scripts/e2e.ps1`) — details in `.agents/skills/run-e2e/SKILL.md` |
| Helm chart checks: lint, unit tests, kubeconform (Docker only, pinned images) | `deploy/helm/scripts/validate.sh` |
| Helm kind smoke install (local only; needs kind, kubectl, JDK 21; `--keep` keeps the cluster) | `./scripts/helm-smoke.sh [--keep]` |
| Loadtest module build + unit tests (compiles the simulations, never runs Gatling) | `./mvnw -B -pl loadtest verify` |
| Load test (local only; NFR-2 ingest and fallback burst, about 15 minutes) | `./scripts/load.sh [--simulation ingest\|burst\|all]` — results in `harness/build/load/summary.json`, details in `docs/testing.md` |
