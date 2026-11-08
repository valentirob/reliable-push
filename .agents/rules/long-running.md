---
trigger: always_on
description: Every wait has a timeout and stops when what it waits on is gone; check that long-running work is really running before saying so.
---

# Long-running commands and waits

A wait loop that cannot end once ran for three days, waiting on a log in a workspace that had been deleted; another
polled `gh` with a short SHA that matched nothing. Both looked like work in progress.

- **No open-ended waits.** Never write `until …; do sleep …; done`, `while true` or a polling loop by hand. Use
  `scripts/wait-until.sh --timeout <duration> [--needs <path>] [--needs-pid <pid>] -- <condition>`: it stops at the
  timeout (exit 124) or as soon as what it waits on is gone (exit 125).
- **Timeout:** about twice the usual duration of what you wait for, and say what happens next if it expires (retry once,
  read the log, ask the maintainer). Never "large enough to be safe".
- **Tie the wait to its producer:** `--needs-pid` of the process that writes the result, `--needs` of the workspace or
  log folder. A condition on a file in a deleted folder never becomes true.
- **Conditions that can match nothing:** check them once by hand before waiting on them (a `gh run list --commit` needs
  the full SHA; a `grep` needs the exact string the tool prints).
- **Processes that do not end on their own** (servers, `--keep` stacks, emulators, dev consoles) are not waits: note how
  to stop them and stop them at the end of the task.
- **Before saying that something is still running**, and at the start of a session, run `scripts/check-waits.sh`. It
  lists the registered waits and flags overdue ones and shell loops without a timeout older than 30 minutes. Stop what
  you started; ask the maintainer about anything else (another agent's work).
- Claude Code (`.claude/settings.json`) and Antigravity (`.agents/hooks.json`) run the check automatically at session
  start and on every prompt or model call, and add any finding to the conversation. Keep both hooks pointing at the
  script; `scripts/sync-agent-skills.sh --check` fails when one is missing.
