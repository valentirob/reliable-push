@AGENTS.md

## Claude Code specifics

The shared context lives in `AGENTS.md` (above) and `.agents/`: Antigravity reads them natively, Claude Code through
this file. Keep this file thin: anything that is not about Claude Code itself goes into `AGENTS.md`, a rule or a skill.

The rules with `trigger: always_on`, loaded here as Antigravity loads them:

@.agents/rules/gitflow.md
@.agents/rules/handoff.md
@.agents/rules/docs-sync.md

- The `glob` and `model_decision` rules are not loaded automatically: read the ones the AGENTS.md rules index says
  apply to the files you touch (`backend-java.md`, `mobile-sdk.md`, `security.md`).
- Skills: `.claude/skills/<name>/SKILL.md` are generated wrappers that point at `.agents/skills/<name>/SKILL.md`.
  Never edit them; edit the source and run `scripts/sync-agent-skills.sh`.
- Subagents in `.claude/agents/` are thin wrappers around the `review-loop` skill roles; keep their logic in the skill.
- Plugins (for example a planning or sub-agent workflow) may drive the work, but their scratch files stay out of git
  (`.superpowers/` is ignored) and their results go into `docs/specs/`, `docs/plans/` and `docs/STATUS.md`.
- Claude Code's own memory is private to one machine: never rely on it for project knowledge (see AGENTS.md "AI agent
  context").
