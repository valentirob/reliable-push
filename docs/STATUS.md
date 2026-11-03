# Status / handoff log

Newest entry first. Updated at the end of every work session (see `.agents/skills/session-handoff/SKILL.md`).

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
