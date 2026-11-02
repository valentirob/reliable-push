---
trigger: always_on
description: Read docs/STATUS.md at session start and update it before ending, so work can continue on any machine with any LLM.
---

# Session handoff

- At session start: read `docs/STATUS.md`, then the active plan in `docs/plans/`.
- While working: tick plan checkboxes as tasks are verified, in the same commit as the work.
- Before ending (or when context is getting long): run the `session-handoff` skill.
- Never leave important context only in chat. Decisions go to the spec (or `docs/decisions/` as a short ADR), progress goes to the plan and `docs/STATUS.md`.
