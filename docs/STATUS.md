# Status / handoff log

Newest entry first. Updated at the end of every work session (see `.agents/skills/session-handoff/SKILL.md`).

## Remaining work

Everything left after `v0.1.0` is in [TODO.md](TODO.md): each item says why it is deferred and which action or date
unblocks it. Agents: remind the maintainer of its section 1 (real APNs and the locked-device check, real FCM, Maven
Central) at the start of a session.

## 2026-11-08 (16) — Claude Code — cycle 5: fallback circuit breaker (0.2.0, on `feature/fallback-circuit-breaker`)

- **Done:** the per-app fallback circuit breaker (FR-18, FR-19; spec
  `docs/specs/2026-11-07-fallback-circuit-breaker-design.md`, plan `docs/plans/2026-11-08-cycle5-fallback-circuit-breaker.md`):
  - Backend package `io.reliablepush.fallback.circuit`: configuration (`fallback.suspended`, `fallback.circuit-breaker.*`),
    migration `V6__fallback_circuit.sql`, detection before every claim under the blocking advisory lock `4242002`
    (2 s `lock_timeout`, fail closed with `fallback.circuit_check_failed`), re-evaluation during long drains,
    suppression as `FALLBACK_EXPIRED` without a call, daily event prune; admin routes
    `GET /v1/admin/fallback-circuits`, `.../{appId}/events`, `POST .../{appId}:resume` (permission `fallback-control`,
    `503` after 5 s on the lock); admin page badge, circuit view and "Resume fallbacks".
  - Task 7: E2E scenario `fallback-circuit` on a dedicated harness app `circuit` (the E2E stack tunes the breaker for all
    apps: window 30 s, `min-due` 10, `close-min-delivered` 3, `min-open` 5 s; no other scenario comes near it, and the
    full run's logs show circuits opening only for `circuit`); Helm `config.fallback` example and a unit test; page fixes
    (badge fetch no longer retries a 503 before the tiles render; the circuit view re-reads the circuit after a 409/404
    resume); every doc of spec §8, the at-a-glance SVG/PNG (breaker in front of the webhook; PNG re-rendered at 2x with
    headless Chrome) and a new presentation slide.
- **Rulings:** the app `Clock` for detection; breaker disabled in backend tests and the load test; resume allowed from
  any state, button only for `OPEN`; `fallback.suppressed` only when rows were closed. Spec §7: the E2E burst
  step asserts fewer than `min-due` calls (guaranteed since the final fix wave: a tick claims only rows its check
  counted); the batch sent while the circuit is open must get none. Observed: 0 calls, all 15 suppressed in one tick.
- **State:** `cd backend && ./mvnw -B verify`: unit 310, IT 316, 0 failures, line coverage 95.2 %; root `./mvnw -B verify`:
  client 34, harness 162 unit + 81 IT, loadtest 19, 0 failures; `deploy/helm/scripts/validate.sh`: 93 unit tests, all
  checks passed; `./scripts/e2e.sh`: `[e2e] summary: 19/19 passed` (2026-11-08). Android and iOS suites not run (no
  SDK change).
- **Open:** TODO 4.8 (due-set indexes without `app_id`) and 4.9 (breaker untested under load); the circuit pages were
  checked by the Playwright smoke only, not by a person; release 0.2.0 is the maintainer's call.
- **Final review fix wave** (commits 6dc6f2d..this entry): (1) a tick takes its check instant once and passes it to
  `CircuitBreaker.evaluate(appIds, at)`, the suppressor and `claimDue` as the due cut-off (lease from the real now; a
  drain re-check advances it), so no claimed row came due after the check and the E2E burst assertion `fired < min-due`
  is guaranteed (spec §4/§7 reworded); (2) metrics `rp_fallback_circuit_transitions_total{app,transition}` (once per
  committed transition, both roles) and `rp_fallback_circuit_check_failed_total` (worker), in `operations.md` with
  alert hints; (3) a `circuit_open` suppression chunk locks the circuit row and stops once it is no longer `OPEN`;
  (4) circuit view shows the suspended message whatever `enabled` is, a 404 resume shows "App not found." once;
  (5) `api.js` retries network errors for GET only; (6) the demo stack sets the breaker back to the production
  defaults (`demo.override.yml`), `testing.md` says so and adds "bursts go to a dedicated app".
  State after the wave: `cd backend && ./mvnw -B verify`: unit 317, IT 319, 0 failures, line coverage 95.3 %, BUILD
  SUCCESS; root `./mvnw -B verify`: client 34, harness 162 unit + 81 IT, loadtest 19, BUILD SUCCESS; `./scripts/e2e.sh`:
  `[e2e] summary: 19/19 passed` (burst: 0 calls, 15 `FALLBACK_EXPIRED`). Not run: Helm validate (no chart change),
  Android/iOS suites (no SDK change). Note: transition timestamps use the tick's check instant, taken before the lock
  wait, so two workers' events can be up to the 2 s lock timeout out of order.
- **Next:** review loop, merge `feature/fallback-circuit-breaker` into `develop` (`--no-ff`), push, CI.

## 2026-11-07 (15) — Claude Code — iOS extension reads nothing for untracked pushes

- **Done:** review of how both SDKs treat a push without `rp_tid`: iOS `ReliablePushExtension.didReceive` and
  `trackOpen`, Android `ReliablePush.handle` and `trackOpen` all check `rp_tid` first and return `false` / do nothing,
  and the SDK declares no `FirebaseMessagingService`. One gap fixed (`bugfix/ios-extension-lazy-config`): the iOS
  extension resolved the stored configuration in its initializer, so every push (the host's own too) opened the App
  Group and could log `sdk.app_group_unavailable`; it now resolves it in `didReceive` after the `rp_tid` check. Tests
  added (no read for foreign or malformed pushes, one read for tracked ones, configuration stored after creation is
  used) and fail on the old code. `docs/ios.md`, overview §8 diagram (no-`rp_tid` edge) and CHANGELOG updated;
  presentation and at-a-glance picture already say untracked pushes are left alone.
- **State:** iOS package tests 115 run, 1 skipped, 0 failures; size gate 291,928 bytes (limit 307,200);
  `./scripts/ios-e2e.sh` `iOS E2E: 8/8 passed, 1 skipped`.
- **Next:** release 0.2.0 when the maintainer decides; TODO section 1.

## 2026-11-07 (14) — Claude Code — ops dashboard doc gaps closed

- **Done:** the four docs-only items of entry 13 (`bugfix/ops-dashboard-doc-gaps`, merged into `develop`):
  `architecture.md` diagram says Flyway V1..V5; `deployment.md` has the `indisvalid` check and the failed-row delete for
  an interrupted `V5` build, and a "Connection poolers" note (direct or session mode; PgBouncer transaction pooling not
  supported because Flyway's migration lock is session-level); spec §2 lists the reserved id `false`; `operations.md`
  notes the two connections the rollup and the retention wait hold, from the shared pool in combined mode.
- **State:** docs only, no code or diagram change (overview, at-a-glance picture and presentation unaffected).
- **Next:** release 0.2.0 when the maintainer decides; TODO section 1 (real APNs and the locked-device check, real FCM,
  Maven Central).

## 2026-11-07 (13) — Claude Code — cycle 4 merged into `develop`

- **Done:** `feature/ops-dashboard` merged into `develop` (`--no-ff`) after the full check: backend `verify -Pimage`, root
  reactor `verify`, Helm `validate.sh`, `./scripts/e2e.sh` `E2E: 18/18 passed`, `./scripts/ios-e2e.sh`
  `iOS E2E: 8/8 passed, 1 skipped`. CI on `develop` first failed in `AdminSearchIT` (a seeded `PENDING` row due in
  March 2026 was claimed by a running worker, so it read back as `FALLBACK_PENDING`); fixed by seeding open rows due in
  2999 (`bugfix/admin-search-it-claimable-rows`). CI green after it.
- **Not verified:** the signed-in dashboard views were not looked at by a person (only the Playwright smoke covers them);
  `scripts/e2e.ps1` was never run (no Windows machine).
- **Open (docs only):** `architecture.md` diagram label still says Flyway V1..V4; `deployment.md` lacks the
  `indisvalid` check for an invalid `notification_app_created` left by a cancelled build; spec §2 does not list the
  reserved id `false`; no note on the combined-mode shared pool for the retention wait or on PgBouncer transaction mode.
- **Next:** release 0.2.0 when the maintainer decides; the docs-only items above (closed in entry 14).

## 2026-11-07 (12) — Claude Code — cycle 4: final-review fix wave (on `feature/ops-dashboard`)

- **Done:** the findings of the final whole-branch review, each test-first:
  - Retention no longer stops for good behind a long rollup: `RollupRetentionGuard` takes the rollup's advisory lock
    (blocking, `lock_timeout = 30min`) before reading `max(computed_at)`; a wait that runs out skips with
    `retention.skipped reason=rollup_running`. `RetentionGuard` now returns the veto reason (`veto()`).
  - Overview backlog query adds `due_at is not null`, so `notification_due` serves it (closed `DELIVERED` rows no longer
    scanned).
  - `notification_app_created` moved from `V4__admin.sql` (unreleased) to `V5__admin_index.sql`
    (`create index concurrently`); `spring.flyway.postgresql.transactional-lock=false`, because the index build
    otherwise hangs on Flyway's open lock transaction (observed: wait on `virtualxid`). Upgrade note in `deployment.md`.
  - Devices lookup audit-logged (`admin.devices_viewed`, `rp.user_hash`).
  - Search result links use the app of the search that produced them.
  - R13: `rollup.failed` also logs the stack trace at ERROR; `/admin/**` sends `Cache-Control: no-cache`; admin key id
    `false` rejected at startup; TODO 4.5-4.7 (oldest live day with `retention-days <= 7`, no backfill, live-query cost).
- **State:** `cd backend && ./mvnw -B verify` passed (unit 272, IT 265, 0 failures; coverage gate met); E2E not run by this wave (the controller runs it).
- **Docs:** `operations.md`, `admin.md`, `security.md`, `api.md`, `deployment.md`, `architecture.md`, spec §3, §3.2, §4,
  §5, `openapi.yaml`, CHANGELOG, TODO. Diagrams and presentation checked: no change needed (no new component or flow).
- **Next:** controller E2E run, then merge `feature/ops-dashboard` into `develop`.

## 2026-11-07 (11) — Claude Code — cycle 4: ops dashboard (0.2.0, on `feature/ops-dashboard`)

- **Done:** the read-only ops dashboard (FR-16, FR-17; spec `docs/specs/2026-11-06-ops-dashboard-design.md`, plan
  `docs/plans/2026-11-06-cycle4-ops-dashboard.md`):
  - Backend module `admin`: admin keys (hashed, scoped, `payload` permission), `/v1/admin/**` API, the page at `/admin/`,
    `fallback_attempt` rows written by the worker, `daily_stats` rollup (`DailyRollupJob`), the retention guard,
    audit events. Migration `V4__admin.sql`.
  - Helm `config.admin`; harness: `admin-dashboard` scenario, `AdminSmokeE2E`, per-run admin key (`HARNESS_ADMINKEY`).
  - Docs (task 9): `docs/admin.md`, `api.md`, `security.md`, `architecture.md`, `deployment.md`, `testing.md`,
    `getting-started.md`, `overview.md` §5 (sections 5-10 became 6-11), at-a-glance SVG and PNG, presentation slide and
    status slide, README, CHANGELOG `Unreleased`, TODO 5.1.
- **Rulings:**
  - R1 admin ITs get their keys from a test profile `admin`; default test contexts keep admin disabled.
  - R2 `RetentionJob` asks an optional `RetentionGuard` interface, implemented in `admin`, so `notifications` does not depend on `admin`.
  - R3 the real page replaces the placeholder `index.html`.
  - R4 with no admin key, `/v1/admin/**` and `/admin/**` answer exactly like an unknown path.
  - R5 page files live in `resources/admin-ui/`, mapped to `/admin/**` only when admin is enabled.
  - R6 admin parameter errors are `400`; the sender and device APIs keep `422`.
  - R7 `/overview` answers `{items}`, `/stats/daily` `{appId, days}`, `/notifications` `{items, nextCursor}`, `/devices` `{items}`.
  - R8 the page was checked by fetch and Playwright; signed-in views were not checked by a person.
  - R9 `#app` is the overview app selector, `<main>` has id `view` (the smoke test contract).
  - R10 task 8 ran the web E2E only; the controller runs the iOS suite once at the end.
  - R11 with `config.admin.keys` the chart also routes `/admin` (Ingress path appended, second OpenShift Route `-admin`, `route.host` required); unchanged without keys. Helm `validate.sh` passes (92 unit tests).
- **State:** E2E expected `18/18 passed` (the controller runs it at the end; not run by the docs task).
- **Open:** `scripts/e2e.ps1` was not run on Windows; the signed-in dashboard views were not checked by a person in a
  browser; release 0.2.0 is the maintainer's call.
- **Next:** review loop, full checks, merge `feature/ops-dashboard` into `develop` (`--no-ff`), push, CI.

## 2026-11-06 (10) — Claude Code — release 0.1.2

- **Done:** release `v0.1.2` (M7 and M12 fixes) through `release/0.1.2`: `main` and tag `v0.1.2`, GitHub release
  page, `develop` back at `0.2.0-SNAPSHOT`. The release check found `testAppFlushDeniedActivityCancelsAtOnce` flaky:
  a flush cancelled before it took `flush.lock` returned `.skipped` since the M7 wait. Fixed on the release branch: a
  cancelled flush returns `.retryLater` on both paths (no caller tells them apart; events stay queued).
- **State:** backend `verify -Pimage`, root reactor `verify`, Helm `validate.sh` (kubeconform 11/11), Android `build`
  passed; iOS 112 tests with 1 skipped after the fix, the flaky test and `EventSenderTests` 30 iterations without a
  failure; size gate 291,928 B. The E2E runs of entry (9) predate the one-line result-code fix.
- **Next:** pick the 0.2.0 scope ([TODO.md](TODO.md) 5.1).

## 2026-11-06 (9) — Claude Code — backlog fixes M7 and M12 (start of 0.2.0)

- **Done:**
  - Maintainer chose the backlog fixes as the first 0.2.0 work (TODO 4.1, 4.2; both removed from TODO).
  - M7, `bugfix/ios-m7-flush-lock-wait`: `EventSender.flush(deadline:)` waits for `flush.lock` until its deadline
    (`FileLock.lock(until:now:)`, polled every 20 ms, a cancelled task never takes it) instead of skipping; without a
    deadline it still skips. `testLogoutCancelsEveryInFlightAppFlush` now also checks a flush waiting for the lock.
  - M12, `bugfix/harness-m12-proxy-bind`: `harness.device-proxy-bind` (`HARNESS_DEVICEPROXYBIND`), default
    `127.0.0.1`; compose sets `0.0.0.0` inside the container (published on `127.0.0.1:8091` only).
  - Docs-sync: `ios.md` §9, `testing.md` (device proxy), `overview.md` §7 and §8 diagrams, spec plan 3 block,
    presentation status slide, CHANGELOG `Unreleased`. At-a-glance picture checked, no change needed.
- **State:** iOS package tests 112 with 1 skipped, size gate 291,928 B; harness `verify` 238 tests green;
  `./scripts/ios-e2e.sh`: `iOS E2E: 8/8 passed, 1 skipped`; `./scripts/e2e.sh`: `E2E: 17/17 passed`.
- **Review loop:** one medium finding fixed: the lock wait polled `tryLock`, which WARNs on every failed open, so an
  unopenable lock file gave up to ~1250 WARNs; an unopenable file now ends the wait with one WARN. Low, kept:
  `withLock(timeout:)` (logout, reconfigure) still WARNs per poll in that case, at most 2 s.
  Low, accepted and documented in `ios.md` §9: an NSE that waits for the lock hands the notification back up to the
  send timeout (default 5 s) later, never past it.
- **Next:** the rest of the 0.2.0 scope ([TODO.md](TODO.md) 5.1).

## 2026-11-06 (8) — Claude Code — docs-sync rule

- **Done:** new always-on rule `.agents/rules/docs-sync.md` (maintainer request): every change updates the docs, the
  Mermaid diagrams, the at-a-glance SVG and PNG and `docs/presentation.html` in the same branch. Linked from
  `AGENTS.md` (rules index and working agreement), `CONTRIBUTING.md` and the `implement-task` skill (step 8).
- **State:** process docs only; overview, picture and presentation checked, no change needed.
- **Next:** the maintainer picks the 0.2.0 scope ([TODO.md](TODO.md) 5.1).

## 2026-11-06 (7) — Claude Code — Dependabot PRs and release 0.1.1

- **Done:**
  - Dependabot PRs approved and merged: #1 (AGP), #2 (root reactor plugins and JUnit), #5 (exec-maven-plugin), #8
    (`gradle/actions/wrapper-validation`). #6 (swagger-request-validator-core 3.0.0, tests only) conflicted with #5, so
    the same bump was applied by hand and the PR closed as superseded.
  - Closed #3, #4, #7 (Temurin 21 to 24): the project stays on Java 21 LTS. `dependabot.yml` now ignores Temurin major
    updates and has a 14-day cooldown, the "at least two weeks old" rule of `libs.versions.toml` and `tools.env`.
  - Consistency fix: AGP back to 9.4.1, because 9.5.1 (from #1) was released on 2026-11-05.
  - Release `v0.1.1` (docs and dependency updates): `main` and tag `v0.1.1`; `develop` is back at `0.2.0-SNAPSHOT`.
  - GitHub release page `v0.1.1` created from the CHANGELOG section (Latest).
  - Closed the next Dependabot PRs: #9 (AGP 9.5.1 again: too new until 2026-11-19; the cooldown did not hold it back)
    and #10 (backend jre stage to the floating `21-jdk-alpine-3.20`: older, unsupported Alpine and an unpinned tag).
    Dependabot deleted both branches; only `main` and `develop` remain.
- **State:** release checks passed locally (backend `verify -Pimage`, root reactor, Helm `validate.sh`, Android
  `build`, iOS 108 tests with 1 skipped) and CI on `develop` was green before the release merge.
- **Open:** the "Dependency review" job fails on every Dependabot PR because the repository's dependency graph is off
  (Settings → Security → Dependency graph); only the maintainer can enable it. See also [TODO.md](TODO.md).

## 2026-11-06 (6) — Claude Code — docs: "at a glance" picture

- **Done:** `docs/images/reliable-push-at-a-glance.svg` (editable source) and `.png` (2x render): your backend, FCM /
  APNs, an iPhone and an Android phone with the tracking module, the reliable-push backend, the fallback clock and
  timeline, and your fallback service sending the SMS. Shown in the README, at the top of `overview.md` and as slide 3
  of `presentation.html` (19 slides, which now loads `images/` next to it).
- **State:** the PNG is rendered with headless Chrome from the SVG. To refresh it after editing the SVG, open the SVG at
  1600x1100 in Chrome with device scale factor 2 and save a screenshot. Docs only, no code change.
- **Next / Open:** see [TODO.md](TODO.md).

## 2026-11-06 (5) — Claude Code — docs: diagrams, guides, presentation

- **Done:**
  - `docs/TODO.md`: the remaining work, with the reason each item is deferred and what unblocks it.
  - `docs/overview.md`: Mermaid diagrams, with the sidecar principle and a failure table, the components, the end-to-end
    flow and states, and one detail diagram per component.
  - `docs/getting-started.md`: run and test each component step by step, including the demo tour and the sample apps.
  - `docs/presentation.html`: self-contained HTML slides, offline, keyboard navigation, light and dark themes.
  - README, `architecture.md` and `integration-guide.md`: the ASCII diagrams are now Mermaid, and the sidecar framing is
    explicit. `android.md` and `ios.md` have a sidecar note.
  - Plan 1A Steps 1 and 2 ticked.
- **State:** all 16 Mermaid blocks parse and render with Mermaid 11.4.1. The slides were checked in a browser at
  1280x800, in light and dark. Docs only, no code change.
- **Next / Open:** see [TODO.md](TODO.md).

## 2026-11-06 (4) — Claude Code — release 0.1.0

- **Done:** first release. `release/0.1.0` from `develop` set every component to `0.1.0` and added `CHANGELOG.md`; it was
  merged into `main` (tag `v0.1.0`) and back into `develop`. `develop` is now `0.2.0-SNAPSHOT` (poms, Android
  `VERSION_NAME` and `ReliablePush.VERSION`, iOS `ReliablePush.version`); the guides and `client-java/README.md` keep
  pointing at the released `0.1.0`.
- **State (verified on `release/0.1.0`):** `cd backend && ./mvnw -B verify -Pimage` OK; `./mvnw -B -pl harness,loadtest
  -am verify` OK; `cd android && ./gradlew build` OK (release AAR 67,327 bytes); iOS package tests 108 run, 1 skipped, 0
  failures; `./scripts/e2e.sh`: `17/17 passed`. The Android and iOS emulator/Simulator suites were not rerun for the
  version-only change (last: `8/8 passed, 1 skipped` each on the cycle 3 tree).
- **Next:** pick the next scope (none planned yet). Maintainer-only: publish the Android SDK to Maven Central (namespace
  and signing key), real FCM / APNs delivery checks with the maintainer's credentials.
- **Open:** as in (3): real-device push delivery unverified; plan 3 minors M7, M12 parked.

## 2026-11-06 (3) — Claude Code — `develop` (cycle 3 merged)

- **Done:** `feature/cycle3-ios-sdk` merged into `develop` with `--no-ff` (`4e015d8`, merge line `E2E: 17/17 passed`,
  `Android E2E: 8/8 passed, 1 skipped`, `iOS E2E: 8/8 passed, 1 skipped`) and pushed. The merged tree is identical to
  the reviewed branch head; package tests rerun on it: 108 run, 1 skipped, 0 failures; size 291,920 bytes. First GitHub
  runs: iOS CI, CodeQL Swift and Client and harness CI green. The CodeQL Android job failed ("didn't build any of it"):
  `org.gradle.caching=true` plus the cache restored by setup-java made `compileReleaseKotlin` FROM-CACHE, so CodeQL saw
  no compilation. Fixed in `bugfix/codeql-android-build-cache` (`--no-build-cache --rerun-tasks` in that step only),
  merged (`8cfc92c`) and pushed; CodeQL then green on all three jobs (backend, Android, Swift).
- **State:** all three cycles of the spec (backend + harness, Android SDK, iOS SDK) are on `develop`; nothing is
  released (`main` has no release from these cycles, no tag).
- **Next:** cut the first release (`release/<semver>` from `develop`, merged into `main`, tag `v<semver>`, back into
  `develop`); the tag also makes the SwiftPM URL snippet in `docs/ios.md` real.
- **Open:** real APNs delivery and the locked-device check (I1, M5) need an Apple Developer Program account; real FCM
  delivery needs the Firebase files (maintainer steps, `docs/testing.md`). Parked minors of plan 3: M7, M12.

## 2026-11-06 (2) — Claude Code — `feature/cycle3-ios-sdk` (plan 3, final fix wave)

- **Done:** the final whole-branch review's fix wave (ruling R21): I1 Data Protection class
  `completeUntilFirstUserAuthentication` on the queue directory and on `events.lock` / `flush.lock` (set on every open,
  WARN `file.protection_failed` if refused); I2 `ReliablePush.deviceId` from the Keychain record (nil after a restore);
  I3 `willPresent` guidance and known limit; M1 doc fix; M2 WARN `token.unavailable status=<OSStatus>`; M3 a
  retry-not-before more than 3600 s ahead counts as expired; M4 async-NSE and foreign-delegate docs; M5 Debug-only ATS
  local-networking exception for the sample NSE (plist split per configuration); M6 `ios-e2e.sh` restores the language
  of a Simulator it did not boot; M8 no clear / persist on a locked first launch; M9 every in-flight app flush is
  cancelled by logout / reconfigure; R19 public log keys `reason status count outcome type call`; R20 User ID in
  `PrivacyInfo.xcprivacy`; the review's "fix now" items (spoof-host tests, size.sh stderr, doc tidy-ups, delivered-test
  timing, Harness comment, `ios-e2e.sh` header, CI `simctl list` step). Spec plan 3 block updated. M7, M12 stay parked.
- **State (not merged, not pushed):** package tests on the Simulator: 108 run, 1 skipped, 0 failures, no warnings;
  `ios/scripts/size.sh`: 291,920 bytes (limit 307,200; was 272,736, one more 16 KB page); `plutil -lint` OK (privacy
  manifest, NSE plists); sample Debug and Release builds OK (`CODE_SIGNING_ALLOWED=NO`); `./scripts/ios-e2e.sh`:
  `iOS E2E: 8/8 passed, 1 skipped`; the M6 restore was checked with `--test notifications-blocked-means-fallback` on a
  booted de-DE Simulator (`1/1 passed`, language restored); actionlint 1.7.12 clean. No harness, backend or Android
  change, so `e2e.sh` / `android-e2e.sh` were not rerun (last: 17/17, 8/8 + 1 skipped).
- **Next:** the maintainer decides the merge into `develop` (`--no-ff`; merge line `E2E: 17/17 passed`, `Android E2E:
  8/8 passed, 1 skipped`, `iOS E2E: 8/8 passed, 1 skipped`); before that, ideally push the branch / open a draft PR so
  `ios-ci.yml` and the CodeQL Swift job run once.
- **Open:** I1 and M5 need an on-device check (the Simulator does not enforce Data Protection): run `apns-real-delivery`
  with a push while the device is locked, in an app built with Data Protection Complete. Size headroom is now about 15 KB.

## 2026-11-06 (1) — Claude Code — `feature/cycle3-ios-sdk` (plan 3, iOS SDK, Task 8 and handoff)

- **Done:** all plan 3 tasks (0 to 8) are implemented and ticked: the iOS SDK (root `Package.swift`, `ios/Sources/ReliablePush`,
  Apple frameworks only), the sample app with NSE and Simulator UI tests, the harness device proxy (`127.0.0.1:8091`,
  online/offline switch) and the optional real APNs sender. Task 8: `.github/workflows/ios-ci.yml` (`macos-26`, Xcode 26.6 at
  `/Applications/Xcode_26.6.app`, iPhone 17 Simulator: package tests, sample Debug and Release builds, size gate, privacy
  manifest lint, `.xcresult` upload on failure; no UI tests, no credentials), a CodeQL Swift job, `docs/ios.md`, integration
  guide §4, README, `AGENTS.md`, `docs/testing.md`, the run-e2e skill and the spec block "Changes during implementation
  (plan 3)". actionlint 1.7.12 is clean.
- **State (verification on this branch, not merged, not pushed):** package tests on the Simulator: 96 run, 1 skipped, 0
  failures; `ios/scripts/size.sh`: 272,736 bytes (limit 307,200); `./mvnw -B -pl harness -am verify` OK (34 client + 151
  harness unit + 81 harness integration, 0 failures); `cd backend && ./mvnw -B verify` OK (240 unit + 218 integration, 0
  failures); `cd android && ./gradlew build` OK (release AAR 67,347 bytes); `./scripts/e2e.sh`: 17/17 passed (summary line
  `[e2e] summary: 17/17 passed`); `./scripts/android-e2e.sh`: `Android E2E: 8/8 passed, 1 skipped`; `./scripts/ios-e2e.sh`:
  `iOS E2E: 8/8 passed, 1 skipped` (`apns-real-delivery` skipped). The CI sample builds and the CodeQL build command were also
  run locally and succeed. The new workflows have not run on GitHub yet (actionlint only).
- **Not run:** Task 7 Step 7 (real APNs delivery): not run, no credentials (maintainer step: `harness/build/apns/AuthKey.p8`,
  `apns.properties`, `IOS_DEVELOPMENT_TEAM`, see `docs/testing.md`).
- **Next:** (1) final whole-branch review of `feature/cycle3-ios-sdk` (from `e8a2933`) and one fix wave; parked minors are
  in `.superpowers/sdd/2026-11-05-cycle3-ios-sdk/progress.md`. (2) The maintainer decides the merge into `develop`
  (`--no-ff`; merge line `E2E: 17/17 passed`, `Android E2E: 8/8 passed, 1 skipped`, `iOS E2E: 8/8 passed, 1 skipped`).
  Nothing is pushed or merged. (3) Optionally run `apns-real-delivery` with the Apple Developer files.
- **Open:** (a) Real APNs delivery and the real NSE run on a device are unverified. (b) No release tag yet, so the package
  URL snippet in `docs/ios.md` is a template. (c) The iOS CI and CodeQL Swift jobs have not run on GitHub (Xcode path and
  simulator name come from the runner-images readme for `macos-26`).

## 2026-11-05 (7) — Claude Code — `feature/cycle2-android-sdk` (plan 2, final fix wave)

- **Done:** R18 fix wave (whole-branch review minors): SDK no longer posts or reports `DELIVERED` for a blocked channel
  group or paused notifications; `register`/`logout` always call back (`Exception`), `TokenStore.save` never throws;
  `Retry-After`/latency clamped to 1 h without overflow; `Sdk.flush` no longer reschedules a job `persist` just
  scheduled; docs retry wording; harness validates the FCM `project_id`, maps any `RuntimeException` in `FcmSender.send`
  to `FAILED(0)`, clears an FCM token only if unchanged (compare-and-clear); `AndroidE2eSummary` fails an expected
  scenario that was SKIPPED (except `fcm-real-delivery`); `android-e2e.sh` usage, early skip of `--test
  fcm-real-delivery`, separate `publishToMavenLocal` call, airplane mode restored to the saved state.
- **State (not merged, not pushed):** `./gradlew build` OK (release AAR 67,347 bytes, gate 102,400); `./mvnw -B -pl
  harness -am verify` OK; `./scripts/e2e.sh`: `E2E: 17/17 passed`; `./scripts/android-e2e.sh` and `--release-lib`:
  `Android E2E: 8/8 passed, 1 skipped` (each after one rerun, see below); `--test fcm-real-delivery` skips in under 1 s.
  Two single-entry failures were environmental and passed on rerun: `token-refresh-near-expiry` (the host stalled 15 min
  inside the test, so the 20 s token had expired: refresh answered 401 by design) and, with `--release-lib`,
  `offline-delivery-is-sent-when-network-returns` (JobScheduler did not start the job for 85 s after its latency, state
  `NET READY`; the OS delay alarm is inexact). No emulator or stack left running.
- **Follow-ups (not done):** `ReliablePushOptions` builder before 1.0; publish the R8 `mapping.txt`; move test hooks out
  of the public `ReliablePush` object.

## 2026-11-05 (6) — Claude Code — `feature/cycle2-android-sdk` (plan 2, Android SDK, Task 8 and handoff)

- **Done:** all plan 2 tasks (0 to 8) are implemented and ticked: the Android SDK (`android/reliablepush`, Kotlin, Kotlin
  stdlib only), the sample app, emulator E2E (`./scripts/android-e2e.sh`), the harness grants / real-devices / Host
  allowlist (closes the earlier open item "console write guard / Host allowlist against DNS rebinding") and the optional
  real FCM sender. Task 8: `.github/workflows/android-ci.yml` (`./gradlew build`, AAR size gate < 102,400 bytes, Gradle
  wrapper validation, test reports on failure; no emulator, no credentials), a CodeQL Android job, Dependabot `gradle` for
  `/android`, `maven-publish` + `signing` (only when `SIGNING_KEY`/`SIGNING_PASSWORD` are set; `publishToMavenLocal` runs,
  the POM lists only `kotlin-stdlib`), `docs/android.md`, integration guide §4, README, `AGENTS.md`, `docs/testing.md`, the
  run-e2e skill and the spec block "Changes during implementation (plan 2)". actionlint 1.7.12 is clean.
- **AAR size:** the release AAR was 98,307 bytes, too close to the gate, so the library's release build is now minified
  with R8 (`reliablepush/proguard-rules.pro`: keeps the public API in `io.reliablepush.android` and `RetryJobService`,
  `-repackageclasses io.reliablepush.android.internal` so internal classes cannot collide with other minified libraries,
  and no `-allowaccessmodification`, so no member is widened): **65,910 bytes**. `javap -public` of the 7 public and
  manifest classes is identical between the debug and release classes. The unit tests run on the debug variant (no
  `testReleaseUnitTest`); the minified code is exercised at runtime by `./scripts/android-e2e.sh --release-lib` (the
  sample's debug build consumes the release AAR from mavenLocal): `Android E2E: 8/8 passed, 1 skipped`. The library's
  own instrumented Keystore test in that run still runs against the debug library (internal class under test).
- **State (verification on this branch, not merged, not pushed):** `cd android && ./gradlew build` OK;
  `./mvnw -B -pl harness -am verify` OK (34 client + 135 harness unit + 79 harness integration tests, 0 failures);
  `cd backend && ./mvnw -B verify` OK (240 unit + 218 integration, 0 failures, coverage gate met);
  `./scripts/e2e.sh`: `E2E: 17/17 passed`; `./scripts/android-e2e.sh` and `./scripts/android-e2e.sh --release-lib`: each `Android E2E: 8/8 passed, 1 skipped`
  (`fcm-real-delivery` skipped). No emulator or stack left running.
- **Not run:** the real-FCM check (Task 7 Step 7): not run, it needs the maintainer's Firebase project
  (`android/sample/google-services.json` and `harness/build/fcm/service-account.json`, see `docs/testing.md`).
- **Next:** (1) final whole-branch review of `feature/cycle2-android-sdk` (from `648ee28`) and a fix wave; the deferred
  minors are in `.superpowers/sdd/2026-11-05-cycle2-android-sdk/progress.md` (Task 7 ones are worth considering).
  (2) The maintainer decides the merge into `develop` (`--no-ff`; merge line `E2E: 17/17 passed` and `Android E2E: 8/8
  passed, 1 skipped`). (3) Optionally run `fcm-real-delivery` with the Firebase files. (4) Then cycle 3 (iOS).
- **Open:** (a) Real FCM delivery unverified until the maintainer runs it. (b) Maven Central release not set up
  (namespace and signing key are the maintainer's). (c) The chart was never installed on a real GKE or OpenShift cluster;
  `scripts/*.ps1` untested on Windows. (d) The Android CI workflow has not run on GitHub yet (validated locally with
  actionlint only); the Gradle actions SHA is `v6.4.0`.

## 2026-11-05 (5) — Claude Code — `develop`

- **Done:** `feature/cycle1c-deploy-load` (with the plan commit) merged into `develop` (`--no-ff`, `e1be03f`) and pushed;
  local branches `feature/cycle1c-deploy-load` and `feature/plan-cycle1c` deleted. The final fix wave was re-reviewed
  clean before the merge. Plans 1A, 1B and 1C (cycle 1: backend, harness, deploy and load) are all on `develop`.
- **State:** the merged tree is identical to the branch head verified in entry (4) (`E2E: 17/17 passed`, NFR-2 passed with
  `nfr2Criterion: true`); on `develop` `./mvnw -B -pl harness,loadtest -am verify` re-run: 34 client + 106 harness unit +
  73 harness integration + 19 loadtest tests, 0 failures. GitHub Actions on `develop` (`e1be03f`): Backend CI ✅, Client
  and harness CI (incl. the new `loadtest` job) ✅, Helm chart CI (first run) ✅, CodeQL ✅. `main` is still the initial
  commit (no release yet).
- **Next:** cycle 2, the Android SDK (spec §7, §7.2): write its implementation plan (`docs/plans/`) from the approved
  spec, then implement. Needs a local Android SDK / emulator for the instrumented and E2E tests (spec §10.1). Then cycle 3
  (iOS).
- **Open:** (a) The chart was never installed on a real GKE or OpenShift cluster (the `BackendConfig` health check and
  its firewall note are unverified on GKE). (b) `scripts/*.ps1` untested on Windows. (c) ~~Console write guard: Host
  allowlist against DNS rebinding~~ — resolved in plan 2 (Task 5). (d) Small leftovers from the plan 1C reviews, left on purpose: two long doc lines
  (`docs/deployment.md`, spec), cosmetic nits in `scripts/load.sh`, and a narrow race in `scripts/helm-smoke.sh` (a
  same-named cluster created during `kind create` would be treated as owned).

## 2026-11-05 (4) — Claude Code — `feature/cycle1c-deploy-load` (plan 1C, Task 9 and final fix wave)

- **Done:** CI and docs for plan 1C; all plan 1C tasks are ticked. `.github/workflows/helm-ci.yml` (runs
  `deploy/helm/scripts/validate.sh`; no cluster, no load job), `harness-ci.yml` job `loadtest` (`./mvnw -B -pl loadtest
  verify`) and path `loadtest/**`, Dependabot docker `/loadtest`; actionlint 1.7.12 clean. New `docs/deployment.md`
  (Secret, values, install and checks, GKE, OpenShift, combined vs split, upgrades, graceful shutdown, sizing, per-IP
  limits); its generic commands were run against the kind smoke cluster (`--keep`), GKE/OpenShift only rendered. README,
  `AGENTS.md`, `docs/testing.md` (load tests, Helm checks), spec "Changes during implementation (plan 1C)", NFR-2 in
  `docs/requirements.md` marked validated.
- **Final fix wave** (after the whole-branch review): removed the `worker.shutdown-timeout` ≤
  `spring.lifecycle.timeout-per-shutdown-phase` startup check (R10: the worker stop is synchronous; grace rule
  `terminationGracePeriodSeconds > preStop 5 s + worker.shutdown-timeout + 30 s`); worker stop tests (graceful-shutdown
  IT with batch-size 1, tick counter in `FallbackScheduler`, `FallbackWorkerLifecycleTest`); chart
  `api.service.annotations` and `gke.backendConfig` (GKE health check on 8081 `/actuator/health/readiness`, on in
  `values-gke.yaml`); PDB follows `autoscaling.api.minReplicas` with the HPA, `*Ref`/`*_ref` keys checked like `-ref`;
  `load.sh` records the effective settings and `nfr2Criterion` in `summary.json`, burst warm-up (`LOAD_BURST_WARMUP`),
  ingest stops after a failed register; `helm-smoke.sh` marks a kept cluster by its control-plane container ID and wipes
  `logs/` on reuse.
- **State (verification after the final fix wave, on this branch, not merged, not pushed):** `cd backend && ./mvnw -B
  verify` 240 unit + 218 integration tests, 0 failures, line coverage 93.3 %; `./mvnw -B -pl loadtest verify` 19 tests;
  `./mvnw -B -pl harness -am verify` 34 client + 106 harness unit + 73 harness integration tests (Task 9, harness and
  client unchanged since); `deploy/helm/scripts/validate.sh` lint (both modes) + 83 helm-unittest tests + kubeconform on
  4 renders (the GKE one includes the `BackendConfig`), all passed; `./scripts/helm-smoke.sh --keep` then
  `./scripts/helm-smoke.sh` (reuse of the kept cluster) 12/12 PASS each (worker rollout during dispatch: 200/200
  triggered once, no ERROR); `./scripts/e2e.sh`: `E2E: 17/17 passed`; `./scripts/load.sh --simulation all`: **NFR-2:
  passed** (ingest PASSED with `nfr2Criterion: true`: register p95 1 ms / p99 28 ms, events p95 2 ms / p99 34 ms, 0 %
  failed, generator CPU 10.8 %; criterion and replica size unchanged, see `docs/operations.md` "Measured capacity");
  burst PASSED (30,000 triggered, 0 failed or expired, 1,553 rows/s); `./scripts/load.sh --simulation burst` on a fresh
  stack PASSED (`notify` p99 1 ms after the 30 s warm-up; 30,000 triggered, 0 failed or expired).
- **Next:**
  1. Review of the final fix wave (range from `17b774c`); the other deferred minors stay as triaged in the SDD ledger.
  2. Then the maintainer decides the merge into `develop` (`--no-ff`; merge line `E2E: 17/17 passed` and the NFR-2 result).
- **Open:** (a) The chart was never installed on a real GKE or OpenShift cluster (render and kubeconform only; the
  `BackendConfig` health check and its firewall note are unverified on GKE). (b) Earlier open items: `scripts/*.ps1`
  untested on Windows; console Host allowlist.

## 2026-11-05 (3) — Claude Code — `feature/cycle1c-deploy-load` (plan 1C, Task 8)

- **Done:** local load runs (`./scripts/load.sh`) and capacity sizing. **NFR-2 passed:** one API replica (2 CPUs / 1 GiB)
  took 1,000 events/s + 500 notifications/s for 5 minutes with 0 % failed, p95 1-2 ms, p99 28-38 ms (pool 5, 10 and 20).
  The default burst (30,000 rows at 1,500/s) drained at the arrival rate (about 1,550 rows/s, criterion ≥ 400); a backlog
  due at once drained at 2,900-4,900 rows/s per worker (3,100-3,400 at 1 CPU), capped by PostgreSQL's 1 CPU. The first
  baseline failed on the generator, not the backend (Gatling container out of ephemeral ports); fixed in the load
  override (one feedback round, no backend change). Results and chart defaults: `docs/operations.md` "Measured capacity".
- **State:** chart defaults revised (API requests 800m / 384Mi; worker max-concurrency 200, pool 20, requests 500m /
  384Mi, limits 1 CPU / 512Mi); `deploy/helm/scripts/validate.sh` passes. Machine: Mac17,2, Colima 4 CPUs / 6 GiB.
- **Next:** Task 9 of plan 1C (CI for chart and loadtest, final verification).
- **Open:** a burst-only run on a freshly started stack fails its p99 ≤ 250 ms assertion (cold JIT, p99 400-460 ms);
  `load.sh --simulation all` warms the API first.

## 2026-11-05 (2) — Claude Code — `develop`

- **Done:** `feature/cycle1b-harness` merged into `develop` (`--no-ff`, `fad89f9`) and pushed; branch deleted. Plans 1A and 1B are both on `develop`.
- **State:** on the merged tree: `./mvnw -B -pl harness -am verify` 34 client + 106 harness unit + 73 harness integration tests, 0 failures; `cd backend && ./mvnw -B verify` 229 unit + 212 integration tests, 0 failures. GitHub Actions on `develop` (`fad89f9`): Backend CI (incl. Trivy from its pinned image) ✅, Client and harness CI (first run) ✅, CodeQL ✅. `main` is still the initial commit (no release yet).
- **Next:** plan 1C: Helm chart for GKE/OpenShift (`deploy/helm/reliable-push`), Gatling load test, API/worker pool sizing. Then the Android SDK cycle, then iOS.
- **Open:** (a) `scripts/e2e.ps1` / `scripts/demo.ps1` untested on Windows. (b) Console write guard: harden with a Host allowlist (127.0.0.1/localhost/[::1], `/hooks/` exempt) against DNS rebinding and match on the normalized path. (c) Backend follow-ups from the 2026-11-04 entry (graceful worker shutdown, claim coalescing, client-abort logging, management port, stale JWKS cap, `api` profile gating).

## 2026-11-05 — Claude Code — `feature/cycle1b-harness`

- **Done:** final whole-branch review of plan 1B and its fix wave. The `console-smoke` flake was root-caused: a stale device-list response in the console's Send push screen could reset a chosen behaviour (e.g. `never` silently sent as `deliver:1s`); fixed with request-sequence guards in all console views and pinned by a deterministic Playwright regression test (held route) plus failure diagnostics. Also: inbox mode select no longer overwritten by the 3 s poll; console defaults to app `shop`; backend errors carry their real status (429 + Retry-After); cross-site guard on console writes (same-origin, JSON bodies); log-hygiene scans the signing private key and DB password; serialized log-tail polls.
- **State:** `./mvnw -B -pl harness -am verify`: 34 client + 106 harness unit + 73 harness integration tests, 0 failures. `./scripts/e2e.sh`: `[e2e] summary: 17/17 passed (expected 17 scenarios)`. Console smoke against `./scripts/demo.sh`: 5/5 runs passed. Not merged, not pushed (`develop` on GitHub lacks the latest local commits).
- **Next:** merge `feature/cycle1b-harness` into `develop` (`--no-ff`), push `develop`, check `backend-ci` and `harness-ci`; then plan 1C (Helm chart, Gatling load test, API/worker pool sizing) and the Android SDK cycle.
- **Open:** (a) `scripts/e2e.ps1` / `scripts/demo.ps1` untested on Windows. (b) Console write guard: harden with a Host allowlist (127.0.0.1/localhost/[::1], `/hooks/` exempt) against DNS rebinding and match on the normalized path. (c) `harness-ci.yml` not yet run on GitHub.

## 2026-11-04 (2) — Claude Code — `feature/cycle1b-harness`

- **Done:** plan 1B complete: zero-dependency Java sender client (`client-java/`), harness (`harness/`: signed-grant device simulator, fake sender, HMAC-verifying fallback receiver, scenario runner, six-screen web console with SSE), local E2E (`./scripts/e2e.sh`, 16 scenarios + `console-smoke`), demo (`./scripts/demo.sh [--realtime]`, console at http://127.0.0.1:8090/), CI workflow `harness-ci.yml` (jobs `client` and `harness`, never E2E), Dependabot entries (maven `/`, docker `/harness`) and `commit-message` prefixes on all entries (`build(deps)`, `ci(deps)`). Docs: `docs/testing.md`, `docs/integration-guide.md` (client usage), README, `AGENTS.md`, `CONTRIBUTING.md`, `.agents/skills/run-e2e/SKILL.md`; spec §10.1/§10.2 drift fixed and noted under "Changes during implementation (plan 1B)".
- **State:** `./mvnw -B -pl client-java verify`: 34 tests, 0 failures (enforcer: no runtime dependencies). `./mvnw -B -pl harness -am verify`: harness 100 unit + 68 integration tests, 0 failures, coverage gate met. `./scripts/e2e.sh`, exit 0: `[e2e] summary: 17/17 passed (expected 17 scenarios)` (2026-11-04). `./scripts/demo.sh` and `-Pconsole-smoke` against the demo stack run as documented. actionlint 1.7.12 clean. Not merged, not pushed.
- **Next:**
  1. Final whole-branch review of `feature/cycle1b-harness`, then merge `feature/cycle1a-backend` and `feature/cycle1b-harness` to `develop` (`--no-ff`); report `E2E: 17/17 passed` in the merge commit.
  2. `gh auth login`, push `main`, `develop`; check the first CI runs (`backend-ci`, `harness-ci`).
  3. Plan 1C: Helm chart, Gatling load test, API/worker pool sizing. Then the Android SDK cycle.
- **Open:** (a) `scripts/e2e.ps1` and `scripts/demo.ps1` (Windows) are untested; PowerShell commands in the docs were not run. (b) Watch for a `console-smoke` first-run flake: once, a smoke run against a freshly started demo stack timed out waiting for the fallback row; two reruns passed and the E2E run passed. (c) `harness-ci.yml` has not run on GitHub yet (actionlint only); the client job builds with JDK 21 and `--release 17`.

## 2026-11-04 — Claude Code — `feature/cycle1a-backend`

- **Done:** whole-branch review of plan 1A (no critical findings) and its fix wave: `TrackingServiceTest` → `TrackingServiceIT`; device tokens of an app no longer configured → generic 401 (`unknown_app`); bare numbers mean minutes for `backoff`/`lease` and days for `device-token-validity`; startup fails when no app is configured; worker drains the due backlog within one tick (bounded by lease/2) with `worker.max-concurrency`; Hikari connection timeout 5 s and pool timeouts answered 503 + `Retry-After: 5`; cancel of an already-closed notification → 409; retention cron configurable and disabled in tests. Docs updated accordingly.
- **State:** `./mvnw -B verify` BUILD SUCCESS: 229 unit + 210 integration tests, 0 failures, line coverage 93.3%. `./mvnw -B verify -Pimage`: 4/4. Not merged to `develop`, not pushed.
- **Next:**
  1. Maintainer decides: merge `feature/cycle1a-backend` into `develop` (`--no-ff`).
  2. `gh auth login`, then push `main`, `develop` (and optionally the feature branch); check the first CI run.
  3. Plan 1B (client-java, harness, `scripts/e2e.sh`, web console), then plan 1C (Helm, Gatling, API/worker load and pool sizing).
- **Open (follow-ups from reviews):** graceful worker shutdown (stop flag on context close); claim coalescing under sustained backlog; client-abort IOException logged as ERROR; `/actuator/prometheus` on a management port (Helm); cap stale JWKS key use (~24 h); `api` profile gates nothing (log active roles at startup). (Dependabot commit-message prefixes: done in plan 1B.)

## 2026-11-03 (3) — Claude Code — `feature/cycle1a-backend`

- **Done:** plan 1A backend implemented (tasks 1-17: schema V1-V3, apps/config validation, sender API, device API with OIDC / signed-grant identity and ES256 device tokens, events, fallback worker with lease and trigger window, signed webhook client, retention, metrics, ECS JSON logging, container image, CI) and documented (task 18): `docs/architecture.md`, `docs/api.md`, `docs/integration-guide.md`, `docs/security.md`, `docs/operations.md`, `docs/testing.md`, README quick start, `AGENTS.md` commands. Spec and `.agents/rules/backend-java.md` updated with the decisions taken during implementation (see "Changes during implementation" at the top of the spec).
- **State:** `cd backend && ./mvnw -B verify` BUILD SUCCESS: 219 unit tests + 195 integration tests, 0 failures; JaCoCo line coverage 93.4% (gate 80%). `./mvnw -B verify -Pimage` BUILD SUCCESS: 4 image tests; image about 105 MB uncompressed (budget 250 MB). The README quick start was run against the built image (register, list, worker claim). Not pushed (no GitHub credentials here); no end-to-end, console or load tests yet (plan 1B/1C).
- **Next:**
  1. Whole-branch review of `feature/cycle1a-backend`, fix findings, merge to `develop` (`--no-ff`).
  2. Run `gh auth login`, then push `main`, `develop` and the feature branch; check the first CI run (Dependabot and the CodeQL schedule only start once the workflows are on the default branch).
  3. Plan 1B: `client-java`, harness (fake OIDC, fallback receiver, device simulator), `scripts/e2e.sh`, web console. Then plan 1C: Helm chart (`deploy/helm/reliable-push`) and Gatling load test.
- **Open:** (a) the `api` profile does not gate any bean: only `worker` does, HTTP endpoints are served by every instance (documented in `docs/operations.md` and the spec; decide whether to gate controllers/filters behind `api`). (b) macOS LibreSSL generates P-256 keys with explicit curve parameters by default, which the backend rejects: the docs use `-pkeyopt ec_param_enc:named_curve`. (c) GitHub push still blocked until `gh auth login`. (d) Do not run pre-V3 and current workers side by side (see `docs/operations.md`).

## 2026-11-03 (2) — Claude Code — `develop`

- **Done:** spec approved by maintainer (`docs/specs/2026-11-03-reliable-push-design.md`, revisions: trigger window, Log4j 2 ECS logging, dependency budget, Spring Boot 4.x, local-only E2E feedback loop, harness web console). Plan 1A written: `docs/plans/2026-11-03-cycle1a-backend.md` (18 tasks + Task 0 prerequisites).
- **State:** no product code yet. This Mac has only Homebrew OpenJDK 27, no JDK 21, no Maven, no Docker runtime → Task 0 needed before Task 2.
- **Next:**
  1. Maintainer reviews plan 1A and picks the execution method.
  2. Task 0 (install JDK 21, Maven, Colima/Docker), then Task 1.
  3. After 1A: write plan 1B (client-java, harness, e2e.sh, web console) and 1C (Helm, Gatling).
- **Open:** GitHub push still blocked until `gh auth login`.

## 2026-11-03 — Claude Code — `feature/agent-config`

- **Done:** repo initialised with gitflow (`main`, `develop`); LLM-agnostic agent setup: `AGENTS.md`, `CLAUDE.md`, `.agents/rules/`, `.agents/skills/`, `.claude/agents/`.
- **State:** no product code yet. Design agreed in conversation (sidecar integration, PostgreSQL-only, OIDC-pluggable device auth, per-notification fallback policy, multi-app single org, modular monolith). Spec not written yet.
- **Next:**
  1. Write the design spec to `docs/specs/2026-11-03-reliable-push-design.md` and `docs/requirements.md`; maintainer review.
  2. Write the implementation plan for cycle 1 (contracts + backend + harness + repo scaffolding: LICENSE, README, docs, CI).
- **Open:** GitHub push not possible from the original machine until `gh auth login` is done.
