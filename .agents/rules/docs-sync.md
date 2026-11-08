---
trigger: always_on
description: Every completed change updates the documentation, the diagrams and the presentation in the same branch.
---

# Docs, diagrams and presentation stay in sync

A change is not done until everything it makes stale is updated, in the same work branch, before the merge to
`develop`. Nobody has to ask for it.

After each change, check every item below and update the ones it affects:

| What | Where |
|---|---|
| Component guides and API docs | `README.md`, `docs/*.md` (`api.md`, `android.md`, `ios.md`, `integration-guide.md`, `architecture.md`, `deployment.md`, `operations.md`, `security.md`, `testing.md`, `getting-started.md`), component `README.md` files |
| Diagrams | the TOML sources in `docs/diagrams/src/` (used by `docs/overview.md`, `README.md`, `architecture.md`, `integration-guide.md`), then `python3 scripts/diagrams.py`; at-a-glance picture: `docs/diagrams/src/glance.toml` |
| Rendered picture | `docs/images/reliable-push-at-a-glance.png`, re-rendered by the same command (static frame, scale 2; needs Chrome or Chromium) |
| Presentation | `docs/presentation.html` (slides, version and status lines) |
| Change log and backlog | `CHANGELOG.md` (an `Unreleased` section until the release), `docs/TODO.md`, `docs/STATUS.md` |

- Keep numbers, names and defaults identical everywhere (timeouts, versions, endpoints, event names).
- Diagrams: never edit a generated SVG or draw a diagram in Mermaid; follow `docs/diagrams/README.md` (style, source
  format) and run `python3 scripts/diagrams.py --check` before committing. Keep each source's `desc` true.
- If nothing needs to change, say so in the handoff entry ("docs checked, no change needed").
- Docs-only commits use `docs: ...`; a code change and its docs may share one commit.
