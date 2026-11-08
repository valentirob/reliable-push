---
name: release
description: Cut a reliable-push release (release/x.y.z branch, version bump in every component, CHANGELOG, docs, tag on main, GitHub release page, back to the next SNAPSHOT on develop). Use only when the maintainer asks for a release or a hotfix.
---

# Release x.y.z

Done this way for v0.1.0 to v0.2.0. Never push to `main` outside this flow (or a `hotfix/x.y.z` branch from `main`).

## 1. Before cutting

- `develop` is green in CI for the commit you release. With `gh`, filter by the **full** SHA
  (`gh run list --commit "$(git rev-parse HEAD)"`): a short SHA silently matches nothing. Check the command once by
  hand, then wait with a bound (rule `long-running.md`):

  ```bash
  scripts/wait-until.sh --timeout 30m --interval 30 --label "CI on $(git rev-parse --short HEAD)" -- sh -c \
    's=$(gh run list --commit "$(git rev-parse HEAD)" --json status -q ".[].status"); [ -n "$s" ] && ! echo "$s" | grep -q -e queued -e in_progress'
  ```

  Then read the conclusions (`gh run list --commit "$(git rev-parse HEAD)"`): finished is not green.
- The last local verification of every component the release contains is recorded in `docs/STATUS.md` (backend
  verify, root verify, iOS tests and size gate, Android build, `e2e.sh`, the device E2E suites, Helm checks).

## 2. Release branch

```bash
git checkout develop && git pull --ff-only
git checkout -b release/x.y.z
```

Replace `x.y.z-SNAPSHOT` (or the previous version) with `x.y.z` in:

| File | What |
|---|---|
| `pom.xml`, `backend/pom.xml`, `client-java/pom.xml`, `payload-java/pom.xml`, `harness/pom.xml`, `loadtest/pom.xml` | `<version>` |
| `backend/src/test/java/io/reliablepush/ContainerImageIT.java` | image tag |
| `android/gradle.properties` | `VERSION_NAME` |
| `android/reliablepush/src/main/kotlin/io/reliablepush/android/ReliablePush.kt`, `android/reliablepush/src/test/kotlin/io/reliablepush/android/VersionTest.kt` | `VERSION` and its test |
| `ios/Sources/ReliablePush/ReliablePush.swift`, `ios/Tests/ReliablePushTests/VersionTests.swift` | `version` and its test |
| `deploy/helm/reliable-push/Chart.yaml` | `version` and `appVersion` |
| `client-java/README.md`, `payload-java/README.md`, `docs/integration-guide.md`, `docs/android.md`, `docs/ios.md` | dependency snippets and the status line |
| `README.md`, `docs/presentation.html` (title kicker, SwiftPM line, status slide), `docs/TODO.md` (section 5) | latest release |
| `CHANGELOG.md` | `## Unreleased` becomes `## x.y.z — YYYY-MM-DD` with a one-paragraph summary |

`git grep -n -E "x\.y\.z-SNAPSHOT|<previous version>"` must then list only history (CHANGELOG, STATUS, plans, specs).

Verify the bump: `./mvnw -B verify`, `cd backend && ./mvnw -B test`, the Android `VersionTest`, the iOS
`VersionTests`, `deploy/helm/scripts/validate.sh`. Commit `chore(release): x.y.z`, then a `docs/STATUS.md` entry
(`docs: status of release x.y.z`).

## 3. Merge, tag, push

```bash
git checkout main && git pull --ff-only
git merge --no-ff release/x.y.z -m "Merge branch 'release/x.y.z' into main"
git tag -a vx.y.z -m "reliable-push x.y.z"
git checkout develop && git merge --no-ff release/x.y.z -m "Merge branch 'release/x.y.z' into develop"
```

On `develop`, set the next `x.(y+1).0-SNAPSHOT` in the code files of the table (not the docs) and commit
`chore: back to x.(y+1).0-SNAPSHOT after x.y.z`. Then push `main`, `develop` and the tag, and delete
`release/x.y.z`.

## 4. GitHub release page

`gh release create vx.y.z --title "reliable-push x.y.z" --notes-file <notes> --verify-tag`. Notes: the CHANGELOG
summary, the Added / Changed (incompatible) / Fixed highlights, and links to `CHANGELOG.md` and, when senders or apps
must change, `docs/integration-guide.md` at the tag. No binaries are attached (Maven Central is TODO 1.3).

## 5. After

Check CI on `main` (bounded wait as in section 1), then tell the maintainer what was released and remind them of TODO section 1.
