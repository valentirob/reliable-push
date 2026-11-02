---
trigger: always_on
description: Gitflow branching, commit and release conventions for reliable-push.
---

# Gitflow

- `main` = released code only. `develop` = integration branch.
- Work branches start from `develop`: `feature/<short-kebab>`, `bugfix/<short-kebab>`.
- Releases: `release/<semver>` from `develop`, merged into `main` (tagged `v<semver>`) and back into `develop`.
- Hotfixes: `hotfix/<semver>` from `main`, merged into `main` and `develop`.
- Merge work branches with `--no-ff` (or a PR to `develop`). Never push directly to `main`.
- Commits: Conventional Commits (`feat(backend): ...`, `fix(android): ...`, `docs: ...`, `test: ...`, `ci: ...`). One logical change per commit.
- Before committing: build and tests for the touched component pass.
