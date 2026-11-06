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
| Diagrams | `docs/overview.md` (Mermaid) and `docs/images/reliable-push-at-a-glance.svg` |
| Rendered picture | `docs/images/reliable-push-at-a-glance.png`, re-rendered from the SVG (1600x1100 at scale 2) whenever the SVG changes |
| Presentation | `docs/presentation.html` (slides, version and status lines) |
| Change log and backlog | `CHANGELOG.md` (an `Unreleased` section until the release), `docs/TODO.md`, `docs/STATUS.md` |

- Keep numbers, names and defaults identical everywhere (timeouts, versions, endpoints, event names).
- If nothing needs to change, say so in the handoff entry ("docs checked, no change needed").
- Docs-only commits use `docs: ...`; a code change and its docs may share one commit.
