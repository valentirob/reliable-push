---
name: run-e2e
description: Run the reliable-push end-to-end scenarios on the local PC (docker-compose harness with backend, PostgreSQL, fake OIDC, fallback receiver, device simulator) and drive the fix-and-rerun feedback loop. Use after changes to backend, harness or SDKs, before merging a feature branch, or when asked to run E2E tests.
---

# Run end-to-end scenarios (local)

E2E runs only on a local PC, never in CI. Humans and agents use the same command.
Spec: `docs/specs/2026-11-03-reliable-push-design.md` §10.1.

> Status: the harness and `scripts/e2e.sh` are built in cycle 1. Until then this
> skill describes the target behaviour.

## Prerequisites
- Docker Desktop or Podman (with compose) running; JDK 21.
- Mobile scenarios (cycles 2–3): Android emulator / Xcode iOS simulator booted.

## Run
1. Full suite: `./scripts/e2e.sh` (Windows: `scripts\e2e.ps1`).
   One scenario: `./scripts/e2e.sh --scenario <name>`. Keep containers for debugging: `--keep`.
2. Read the exit code (`0` = all passed), then `harness/build/e2e/summary.json`.
3. On failure, read `failure.expected`, `failure.actual`, `failure.hint`, and the
   matching logs in `harness/build/e2e/logs/` (search by `trace.id` / `rp.tracking_id`).

## Feedback loop (agents)
1. For each failed scenario, find the smallest unit or integration test that
   reproduces it; write it, confirm it fails.
2. Fix the code; run the component's unit/IT tests.
3. Rerun the failed scenario, then the full suite.
4. Stop when the suite passes, or after 3 iterations: then record remaining
   failures, logs and hypotheses in `docs/STATUS.md`.
5. Do not weaken or delete a scenario to make it pass. Record flaky or slow
   scenarios and coverage gaps as new plan tasks.

## Report
State the result in the merge commit / PR description, e.g.
`E2E: 14/14 passed (./scripts/e2e.sh, 2026-11-03)`, and in `docs/STATUS.md`.
