---
name: implement-task
description: Implement the next unchecked task from the active plan in docs/plans/ using test-first development, verify it, and commit on a gitflow feature branch. Use when asked to continue, build, or implement work in this repo.
---

# Implement the next plan task

1. Read `docs/STATUS.md` and the active plan in `docs/plans/`. Pick the first unchecked task.
2. Make sure you are on the right `feature/*` branch from `develop` (create it if the plan says so).
3. Read the rules that apply (see the AGENTS.md rules index).
4. Write the failing test(s) described by the task. Run them and confirm they fail for the expected reason.
5. Write the minimal code to pass. Run the component's test command and read the output.
6. Refactor if needed, re-run tests.
7. Run the `review-loop` skill on the diff when the task is non-trivial.
8. Tick the task checkbox in the plan, commit code + plan together with a Conventional Commit message.
9. Continue with the next task, or run `session-handoff` if stopping.

Never mark a task done when tests were not run or did not pass; record the blocker in `docs/STATUS.md` instead.
