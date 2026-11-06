---
name: session-handoff
description: Before ending a work session (or when context is long), update docs/STATUS.md and the active plan so another person or agent, on any machine and with any LLM, can continue. Use when stopping, switching machines, or when asked for a handoff.
---

# Session handoff

1. Run the tests of every component you touched; note pass/fail with the command used.
2. Make sure finished plan tasks are ticked and committed.
3. Update `docs/STATUS.md`:
   - **Date / tool / branch** of this session.
   - **Done**: what changed (link commits or files).
   - **State**: what builds, what passes, what is broken or unverified.
   - **Next**: the next 1–3 concrete steps (plan task ids).
   - **Open questions / decisions needed** from the maintainer.
4. Commit (`docs: update status handoff`) and push the branch if credentials are available; otherwise say it is not pushed.
