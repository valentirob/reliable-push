---
name: run-e2e
description: Run the reliable-push end-to-end scenarios on the local PC (docker-compose stack: backend API and two workers, PostgreSQL, harness with fallback receiver, device simulator and fake sender) and drive the fix-and-rerun feedback loop. Use after changes to backend, harness or SDKs, before merging a feature branch, or when asked to run E2E tests.
---

# Run end-to-end scenarios (local)

E2E runs only on a local PC, never in CI. Humans and agents use the same command.
Spec: `docs/specs/2026-11-03-reliable-push-design.md` §10.1.

## Prerequisites
- Docker with the compose v2 plugin running (Docker Desktop, Colima or Podman); `curl`; ports 8080 and 8090 free
  on 127.0.0.1. No JDK or Maven needed on the host: the images build inside Docker.
- On a Mac with Colima: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.
- Mobile scenarios (cycles 2–3): Android emulator / Xcode iOS simulator booted.

## Run
1. Full suite: `./scripts/e2e.sh` (Windows, PowerShell 7: `pwsh scripts/e2e.ps1`). About 3.5 minutes after the first
   image build (the crash scenarios alone take about 1.5 minutes).
   One scenario: `./scripts/e2e.sh --scenario <name>` (names: `curl -s http://127.0.0.1:8090/console/api/scenarios`
   on a kept stack, or the scenario classes in `harness/src/main/java/io/reliablepush/harness/scenarios/catalog/`).
   Keep the stack running afterwards for debugging or the console (http://127.0.0.1:8090): `--keep`; stop it with
   `docker compose -p reliable-push-e2e -f harness/compose/docker-compose.yml down -v`.
   `E2E_READY_TIMEOUT=<seconds>` changes the readiness wait (default 120).
2. Read the exit code (`0` = every scenario PASSED), then `harness/build/e2e/summary.json`
   (`[{scenario, status, durationMs, failure: {expected, actual, hint} | null, logs}]`); JUnit XML in
   `harness/build/e2e/junit/e2e.xml`.
3. On failure, read `failure.expected`, `failure.actual`, `failure.hint` and the scenario's `logs`, then the matching
   container logs in `harness/build/e2e/logs/` (`<service>.log` from `docker compose logs`, plus the backend JSON log
   files `backend-api.json`, `backend-worker-a.json`, `backend-worker-b.json`; search by `rp.tracking_id` / `trace.id`).
   Raw harness answers are in `harness/build/e2e/responses/`.

## What the script does
- Fails fast if ports 8080/8090 are in use or `docker compose` is missing; removes a stack left by an earlier `--keep`.
- Builds the images (`backend/Dockerfile`, `harness/Dockerfile`), runs the one-shot `secrets` service
  (`harness/compose/GenSecrets.java` → `harness/build/e2e/secrets.env` and `db.env`, git-ignored, new secrets every
  run), starts `postgres`, `api` (profiles `api,json,e2e`), `worker-a`/`worker-b` (`worker,json,e2e`) and `harness`,
  and waits for every readiness probe (on timeout it names the container and prints its last log lines).
- Runs the catalogue through `POST /console/api/scenarios/run` (fast clock: 1 product minute = 1 s), then drives each
  worker-crash scenario: stop `worker-b`, `prepare`, wait until the webhook call is in flight
  (`GET /console/api/inbox/in-flight?appId=shop`), `docker compose kill worker-a`, wait 3 s (inside the trigger window)
  or 25 s (outside), start both workers, `verify`.
- Writes the reports, copies the logs, and runs `down -v` unless `--keep`.

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
