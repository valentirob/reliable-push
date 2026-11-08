---
name: plan-feature
description: Turn a new requirement or design change for reliable-push into an approved spec and an implementation plan (docs/specs, docs/plans) before any code. Use when the maintainer asks for a new feature, an evolution, a redesign, or picks the next release scope.
---

# Plan a feature: spec, then plan, then code

Every evolution so far (cycles 1a to 6) went through this path. Small bug fixes do not: they use `implement-task`
directly on a `bugfix/*` branch.

## 1. Understand before proposing

1. Read `docs/STATUS.md`, `docs/TODO.md` (section 5 is the next scope), `docs/requirements.md` and the specs in
   `docs/specs/` that the change touches.
2. Ask the maintainer the questions that change the design, one topic at a time. Do not just agree:
   - make counter-proposals when a better option exists, with its trade-offs;
   - say plainly when an approach stays invasive for the sender teams or the apps, or contradicts an earlier decision;
   - check claims about third-party products and platform limits (FCM, APNs, store policies) on the web before you
     rely on them, and give the sources.
3. Write back your understanding (goal, constraints, success criteria, what is out of scope) and let the maintainer
   correct it.

## 2. Spec (`docs/specs/YYYY-MM-DD-<topic>-design.md`)

- Header: status, the requirements it changes (`FR-n`, `NFR-n`; add new ones to `docs/requirements.md`), the base specs
  it changes, the target release.
- Section 0: purpose and the decisions taken with the maintainer, numbered (`D1`, `D2`, ...), each with its reason.
- Then the design per component, error handling, data and migrations (the next free Flyway `V<n>`), API changes
  (`docs/api.md`, `backend/src/main/resources/openapi.yaml`), incompatible changes, testing, docs to update.
- Commit it on the feature branch, then ask the maintainer to review the written spec. **No plan before the spec is
  approved; no code before the plan exists.**

## 3. Plan (`docs/plans/YYYY-MM-DD-cycle<N>-<topic>.md`)

- Header: goal, architecture in a few lines, tech stack, link to the spec (`§n` refers to it).
- **Global constraints:** branch name, environment, version floors, rules that apply to every task.
- **Review focus:** the inputs or failure modes the spec implies but no test covers yet, each with the task that adds
  the test.
- Tasks, each a reviewable unit with its own tests: files to create or modify, the interfaces it consumes and
  produces (exact names and signatures), checkbox steps (failing test, run it, implement, run, commit), and the
  verification command with the output that means it passed. The last task is docs, diagrams and presentation
  (`docs-sync` rule).
- Commit the plan, then execute it task by task with `implement-task`. A tool with its own plan executor or
  sub-agents may drive the execution, but the spec and plan files above stay the source of truth and the checkboxes
  are ticked in the same commits as the work.

## 4. Finish

- Final review of the whole branch with `review-loop`, all component tests, the E2E suites the change touches
  (`run-e2e`, and `./scripts/ios-e2e.sh` / `./scripts/android-e2e.sh` for SDK changes).
- Merge into `develop` as the gitflow rule and the AGENTS.md working agreement say, then `session-handoff`.
- A release happens only when the maintainer asks for it: `release` skill.
