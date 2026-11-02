---
name: review-loop
description: Run independent review passes (correctness, security, tests/coverage) over the current branch diff and fix findings until a pass comes back clean. Use before merging a feature branch or when asked to review.
---

# Review loop

Review the diff against `develop` (`git diff develop...HEAD`). Run each role as an
independent pass. If your tool supports subagents or parallel agents, give each
role to a separate agent with only the diff, the relevant rules and the spec;
otherwise run them one after another in a fresh mindset.

## Roles

1. **Correctness reviewer** — logic bugs, race conditions (fallback vs. delivery), error handling, contract drift from `openapi.yaml` and the spec.
2. **Security reviewer** — apply `.agents/rules/security.md`; auth bypass, token handling, input validation, secrets, log leaks.
3. **Test reviewer** — missing tests for new behaviour, weak assertions, untested edge cases; run the test suite and coverage.
4. **Policy reviewer** (only for `android/**`, `ios/**`) — apply `.agents/rules/mobile-sdk.md` and store policies.

## Each finding

`file:line — severity (high/medium/low) — problem — concrete failure scenario — suggested fix`.
Drop findings you cannot back with a concrete scenario.

## Loop

1. Collect findings from all roles.
2. Fix high and medium findings (test first for bugs). Record accepted low findings as plan tasks or in `docs/STATUS.md`.
3. Re-run tests, then re-run the roles that had findings.
4. Stop when a full pass has no high/medium findings, or after 3 iterations (then report what is left).
