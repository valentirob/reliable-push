---
name: run-e2e
description: Start the reliable-push harness (backend, PostgreSQL, fake OIDC, fake sender, fallback receiver, device simulator) and run the end-to-end scenarios. Use to verify cross-component behaviour or after backend changes.
---

# Run end-to-end scenarios

Status: the harness is not built yet. When it lands, this skill must contain the
exact commands. Expected shape:

1. `docker compose -f harness/docker-compose.yml up -d --build`
2. Wait for `backend` health (`/actuator/health` = UP).
3. Run scenarios: delivered-before-timeout (no fallback), read policy, not-delivered (fallback fired once), late delivery after fallback, multi-device, token refresh near expiry, rate limiting.
4. Collect the report from `harness/build/reports/`, then `docker compose ... down -v`.
5. On failure: read backend and harness logs, add a failing test at the lowest level that reproduces it, fix, rerun.
