# Status / handoff log

Newest entry first. Updated at the end of every work session (see `.agents/skills/session-handoff/SKILL.md`).

## 2026-11-03 — Claude Code — `feature/agent-config`

- **Done:** repo initialised with gitflow (`main`, `develop`); LLM-agnostic agent setup: `AGENTS.md`, `CLAUDE.md`, `.agents/rules/`, `.agents/skills/`, `.claude/agents/`.
- **State:** no product code yet. Design agreed in conversation (sidecar integration, PostgreSQL-only, OIDC-pluggable device auth, per-notification fallback policy, multi-app single org, modular monolith). Spec not written yet.
- **Next:**
  1. Write the design spec to `docs/specs/2026-11-03-reliable-push-design.md` and `docs/requirements.md`; maintainer review.
  2. Write the implementation plan for cycle 1 (contracts + backend + harness + repo scaffolding: LICENSE, README, docs, CI).
- **Open:** GitHub push not possible from the original machine until `gh auth login` is done.
