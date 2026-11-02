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
| `backend/` | Tracking + fallback service (modular monolith, `api`/`worker` profiles) | Java 21, Spring Boot 3, PostgreSQL, Flyway |
| `android/` | Android SDK (FCM v1 data mode + opt-in AndroidConfigV2 mode) | Kotlin, minSdk 26 |
| `ios/` | iOS SDK + Notification Service Extension helper | Swift, iOS 15+, SPM |
| `harness/` | Fake sender, fake fallback receiver, device simulator, E2E scenarios | Java / docker-compose |
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

Filled in as components land; keep this section accurate.

| Task | Command |
|---|---|
| Backend build + unit tests | `cd backend && ./mvnw verify` *(after backend scaffold)* |
| E2E | see `.agents/skills/run-e2e/SKILL.md` *(after harness)* |
