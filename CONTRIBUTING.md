# Contributing to reliable-push

Thanks for your interest in contributing. This project is MIT-licensed; by
contributing you agree that your work is released under the same license.

## Before you start

- Read `docs/requirements.md` (scope) and the approved spec in `docs/specs/`.
  Do not contradict the spec; propose a spec change in an issue or PR instead.
- Check `docs/STATUS.md` and the plan in `docs/plans/` to see what is next.

## Branching (gitflow)

- `main` holds released code only; `develop` is the integration branch.
- Start work from `develop` on `feature/<short-kebab>` or `bugfix/<short-kebab>`.
- Releases use `release/<semver>`, hotfixes use `hotfix/<semver>`.
- Merge with `--no-ff` or through a PR to `develop`. Never push directly to `main`.

Full rules: `.agents/rules/gitflow.md`.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/), one logical
change per commit, in English:

```
feat(backend): add notification registration endpoint
fix(android): retry event upload on 5xx
docs: clarify webhook signature verification
test: cover duplicate READ events
ci: add dependency review job
```

## Test first

No production code without a failing test that requires it. Write the test,
watch it fail, implement, watch it pass. Build and tests for the component you
touched must pass before you commit; read the output instead of assuming.

## Review loop

Before merging a feature branch, run independent review passes (correctness,
security, tests, and mobile policy where relevant) and fix findings until clean.
The roles are defined in `.agents/skills/review-loop/SKILL.md`.

## Docs, diagrams and presentation

Every change updates, in the same branch, the documentation it makes stale:
the guides in `docs/`, the README, the Mermaid diagrams in `docs/overview.md`,
the at-a-glance picture (`docs/images/reliable-push-at-a-glance.svg`, with its
PNG re-rendered), `docs/presentation.html` and `CHANGELOG.md`. Full rule:
`.agents/rules/docs-sync.md`.

## End-to-end tests run locally

E2E scenarios run only on a developer machine, never in CI:
`./scripts/e2e.sh` (Windows: `pwsh scripts/e2e.ps1`). Report the result in the
merge commit or PR description, for example
`E2E: 23/23 passed (./scripts/e2e.sh, 2026-11-08)`. See
`.agents/skills/run-e2e/SKILL.md`.

## Secrets

Never commit secrets. Use `.env.example` / `application-example.yml` with
placeholders. Report vulnerabilities privately as described in `SECURITY.md`.

## AI coding agents

Agents (Claude Code, Antigravity, others) must follow `AGENTS.md`, the rules in
`.agents/rules/` and the skills in `.agents/skills/`, and must update
`docs/STATUS.md` at the end of a session. Every tool gets the same context from
those files: Antigravity reads them natively, Claude Code through `CLAUDE.md`
and the generated wrappers in `.claude/skills/`. After changing a skill, run
`scripts/sync-agent-skills.sh` (CI runs it with `--check`).
