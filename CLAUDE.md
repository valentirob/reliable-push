@AGENTS.md

## Claude Code specifics

- Rules in `.agents/rules/` are not auto-loaded by Claude Code: read the ones listed in the AGENTS.md rules index that apply to your task.
- Skills: `.claude/skills` is a symlink to `.agents/skills`, so the same skills work in Claude Code and Antigravity.
- Subagents in `.claude/agents/` are thin wrappers around the `review-loop` skill roles; keep their logic in the skill, not here.
