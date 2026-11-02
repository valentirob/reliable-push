# Status / handoff log

Newest entry first. Updated at the end of every work session (see `.agents/skills/session-handoff/SKILL.md`).

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
