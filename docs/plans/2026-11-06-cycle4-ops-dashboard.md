# Ops Dashboard (cycle 4, 0.2.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A read-only ops dashboard served by the backend: admin API keys, persisted webhook attempts, daily rollups,
an admin API and a static page, with harness E2E coverage and every doc, diagram and slide in sync.

**Architecture:** New backend module `io.reliablepush.admin` (filters, controller, SQL queries, rollup job) next to the
existing modules; one Flyway migration `V4__admin.sql`; static files under `backend/src/main/resources/static/admin/`.
The fallback worker writes one `fallback_attempt` row per webhook call inside its existing fenced outcome update.

**Tech Stack:** Java 21, Spring Boot 4 (web MVC, JdbcClient, Flyway, scheduling), PostgreSQL 16, Log4j 2 ECS,
JUnit 5 + AssertJ + Testcontainers + swagger-request-validator (`OpenApiAssert`), Playwright (harness smoke), plain
HTML/CSS/JS ES modules, Helm.

**Spec:** [`docs/specs/2026-11-06-ops-dashboard-design.md`](../specs/2026-11-06-ops-dashboard-design.md) (approved
2026-11-06). Section numbers below (`§n`) refer to it.

## Global Constraints

- Work on `feature/ops-dashboard` (gitflow); Conventional Commits; commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Environment: `JAVA_HOME=/opt/homebrew/opt/openjdk@21`, its `bin` first on `PATH`, `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- Backend check: `cd backend && ./mvnw -B verify` (unit + IT + 80 % line coverage gate) must pass at the end of every backend task.
- No new runtime dependency anywhere; no ORM, no Spring Security (`JdbcClient` and servlet filters, as the existing modules).
- The page uses no third-party code, no CDN, no inline script or style, no build step.
- Read-only: no admin route other than `GET` (§0 D5).
- Config prefix `reliable-push.admin`; defaults `rate-limit-per-minute: 600`, `rollup-retention-days: 400`, `rollup.cron: "0 7 3 * * *"`, `keys: []` (§2).
- Admin disabled (no key) ⇒ every `/v1/admin/**` and `/admin/**` request answers the standard `404` problem (§1).
- Logs: never the admin key, a `userId` or a payload; user identity only as `rp.user_hash` (`UserHash.of`). Every new log event is added to the event table of `docs/operations.md` **in the task that introduces it** (the harness `log-catalogue` scenario fails otherwise).
- Every admin response and error is described in `backend/src/main/resources/openapi.yaml` and checked with `OpenApiAssert.assertMatchesContract` in the IT that exercises it.
- English for code, comments, docs and commits; docs-sync rule (`.agents/rules/docs-sync.md`) applies; Task 9 does the cross-cutting docs.

## Review Focus

1. A `userId` with spaces, `/`, `<`, `&`, non-ASCII characters → search and devices find it, the page shows it as text and the hash route round-trips it (Task 4 IT, Task 8 smoke).
2. A range with `from >= to`, or a span over 400 days, or a `to` in the future → `400` for the first two; a future `to` is accepted (§4 half-open ranges) (Task 4 and Task 6 ITs).
3. An app with no notifications at all → overview zeros with `null` rates, trends with zero rows, never `500` or a division by zero (Task 5 and Task 6 ITs).
4. A notification created at `23:59:59.999Z` and one at `00:00:00Z` the next day → counted in two different UTC days (Task 6 IT).
5. A pasted key with surrounding spaces or a trailing newline → the page trims it before `GET /me`; the API compares the header exactly (Task 3 IT, Task 8 smoke).

---

### Task 1: Admin configuration and key lookup

**Files:**
- Create: `backend/src/main/java/io/reliablepush/admin/AdminProperties.java`, `AdminKeys.java`, `AdminPrincipal.java`, `AdminConfig.java`
- Modify: `backend/src/main/java/io/reliablepush/apps/AppRegistry.java` (expose `boolean isSenderKeyHash(byte[] sha256)`)
- Test: `backend/src/test/java/io/reliablepush/admin/AdminKeysTest.java`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("reliable-push.admin") record AdminProperties(@DefaultValue("600") @Min(1) int rateLimitPerMinute, @DefaultValue("400") @Min(1) int rollupRetentionDays, @DefaultValue Rollup rollup, @DefaultValue List<Key> keys)`; `record Rollup(@DefaultValue("0 7 3 * * *") String cron)`; `record Key(String id, String keySha256, List<String> apps, @DefaultValue List<String> permissions)`.
  - `record AdminPrincipal(String keyId, Set<String> apps, boolean payload)` with `static final String ATTR = "rp.admin_principal"` and `boolean canSee(String appId)`.
  - `@Component class AdminKeys` with `AdminKeys(AdminProperties, AppRegistry)`, `boolean enabled()`, `Optional<AdminPrincipal> byRawKey(String rawKey)` (SHA-256 then `MessageDigest.isEqual` over every key, no early exit), `int ratePerMinute()`.

- [ ] **Step 1: Write the failing tests** in `AdminKeysTest` (build `AdminKeys` from `AdminProperties` + `AppFixtures` registry with apps `shop`, `other`):
  - `noKeysMeansDisabled`: `enabled()` is false, `byRawKey("x")` empty.
  - `matchesByHashAndExpandsStar`: key `k1` with `apps: ["*"]` → `byRawKey(raw)` gives `apps == {"shop","other"}`, `payload == false`; key with `permissions: ["payload"]` → `payload == true`.
  - `scopedKeySeesOnlyItsApps`: `apps: ["shop"]` → `canSee("shop")`, not `canSee("other")`.
  - `unknownOrBlankKeyIsEmpty`: `byRawKey("")`, `byRawKey(null)`, `byRawKey("nope")` are empty.
  - `startupFailures` (parameterised, each `assertThatThrownBy(...).isInstanceOf(IllegalStateException.class).hasMessageContaining(<id>)`): duplicate id; id `"Bad_Id"`; `keySha256` of 63 chars and of upper-case hex; two keys with the same hash; hash equal to the `shop` sender key hash; `apps: []`; `apps: ["*","shop"]`; `apps: ["nope"]`; `permissions: ["write"]`.
- [ ] **Step 2: Run** `cd backend && ./mvnw -B test -Dtest=AdminKeysTest` — expected: compilation failure (classes missing).
- [ ] **Step 3: Implement** the three classes and `AppRegistry.isSenderKeyHash`; register `AdminProperties` with `@EnableConfigurationProperties` in a new `AdminConfig` (`@Configuration(proxyBeanMethods = false)`). Validation runs in the `AdminKeys` constructor (id pattern `[a-z0-9-]{1,40}`, hash `[0-9a-f]{64}`).
- [ ] **Step 4: Run** the test again — expected: PASS; then `./mvnw -B verify` — expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `feat(backend): admin keys configuration and lookup`.

### Task 2: Webhook attempts are persisted

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__admin.sql` (both tables of §3.1/§3.2 and index `notification_app_created`), `backend/src/main/java/io/reliablepush/fallback/FallbackAttemptRepository.java`, `FallbackAttempt.java`
- Modify: `backend/src/main/java/io/reliablepush/fallback/FallbackDispatcher.java` (constructor gains `FallbackAttemptRepository`, `TransactionTemplate`)
- Test: `backend/src/test/java/io/reliablepush/fallback/FallbackAttemptIT.java`, `backend/src/test/java/io/reliablepush/SchemaIT.java` (extend)

**Interfaces:**
- Produces: `record FallbackAttempt(UUID notificationId, int attempt, Instant startedAt, long durationMs, String outcome, Integer httpStatus, String reason)`; `FallbackAttemptRepository.insert(FallbackAttempt)`, `List<FallbackAttempt> forNotification(UUID id)` (ordered by `attempt`).
- Consumes: existing `NotificationRepository.close(...)` / `reschedule(...)` (fenced, return `boolean`).

- [ ] **Step 1: Write the failing tests** in `FallbackAttemptIT` (extends `PostgresTestBase`, profiles `api,worker,test`, WireMock as receiver like `FallbackSchedulerIT`):
  - `successWritesOneSuccessAttempt`: receiver 200 → one row `attempt=1, outcome="SUCCESS", httpStatus=200, reason=null`, `durationMs >= 0`.
  - `retryThenSuccessWritesTwoRows`: receiver 503 then 200 → rows `(1,"RETRYABLE",503,"http_503")`, `(2,"SUCCESS",200,null)`.
  - `finalFailureWritesFinal`: receiver 400 → `(1,"FINAL",400,"http_400")`.
  - `expiryWithoutCallWritesNothing`: a row claimed after `due_at + window` → `FALLBACK_EXPIRED`, zero attempt rows.
  - `staleHolderWritesNoAttempt`: the receiver answers 200 after a 500 ms delay; during that delay the test moves the row's `lease_until` (`update notification set lease_until = lease_until + interval '1 second'`, as a re-claim would) → the fenced update returns false, the row stays `DISPATCHING`, and `forNotification` is empty.
  - `attemptsGoWithTheNotification`: `RetentionJob.purge()` on a closed old row removes its attempts (cascade).
  - `SchemaIT`: tables `fallback_attempt`, `daily_stats` and index `notification_app_created` exist.
- [ ] **Step 2: Run** `./mvnw -B verify -Dit.test=FallbackAttemptIT,SchemaIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` — expected: FAIL (table missing).
- [ ] **Step 3: Implement.** In `FallbackDispatcher.handle`, record `Instant startedAt = now()` before `webhook.send`; build the `FallbackAttempt` from the `WebhookResult` (`Success`→`SUCCESS` + status; `Retryable`/`Final` → `RETRYABLE`/`FINAL`, `httpStatus` parsed from a reason `http_<nnn>` else null, `reason` = `reason(result)`). Every outcome **after a call** (Done, Fail, RetryAt, and Expire `next_retry_after_trigger_window`) runs `transactions.execute(s -> { boolean ok = <fenced update>; if (ok) attempts.insert(a); return ok; })`; outcomes without a call are unchanged.
- [ ] **Step 4: Run** the ITs, then `./mvnw -B verify` — expected: PASS / BUILD SUCCESS.
- [ ] **Step 5: Commit** `feat(backend): persist each fallback webhook attempt`.

### Task 3: Admin filters, `/me`, page gate

**Files:**
- Create: `backend/src/main/java/io/reliablepush/admin/AdminKeyFilter.java`, `AdminPageFilter.java`, `AdminController.java` (only `/me` here), `AdminFilterConfig.java`
- Modify: `backend/src/main/resources/openapi.yaml` (security scheme `AdminKey` = header `X-Admin-Key`; path `/v1/admin/me`; `401`, `404`, `429` responses), `backend/src/test/resources/application-test.yml` (two admin keys, below), `docs/operations.md` (events `auth.failed` reasons `missing_admin_key`, `unknown_admin_key`; `rate_limit.exceeded` scope `admin`)
- Test: `backend/src/test/java/io/reliablepush/admin/AdminApiIT.java`, `AdminDisabledIT.java`

**Interfaces:**
- Consumes: `AdminKeys`, `AdminPrincipal` (Task 1); existing `RateLimiter.tryConsume(String bucket, int perMinute)`, `retryAfterSeconds(String)`, `ProblemWriter`.
- Produces: request attribute `AdminPrincipal.ATTR` on every authenticated `/v1/admin/**` request; `GET /v1/admin/me` → `{"keyId","apps":[sorted],"permissions":[...]}`.

Test keys (raw values only in tests; config holds their SHA-256): `admin-all` = raw `test-admin-key-all`, `apps: ["*"]`, `permissions: [payload]`; `admin-shop` = raw `test-admin-key-shop`, `apps: [shop]`. `rate-limit-per-minute` stays 600; the 429 test uses a dedicated key `admin-limited` with its own test overriding `reliable-push.admin.rate-limit-per-minute=2` via `@TestPropertySource` in a separate IT class `AdminRateLimitIT`.

- [ ] **Step 1: Write the failing tests:**
  - `AdminApiIT.meShowsKeyScope`: `admin-shop` → `200`, body `keyId=admin-shop`, `apps=["shop"]`, `permissions=[]`; header `Cache-Control: no-store`; contract-checked.
  - `AdminApiIT.missingAndUnknownKeyGetTheSame401`: no header and `X-Admin-Key: nope` → identical `401` bodies, `WWW-Authenticate: AdminKey realm="reliable-push"`; the captured log has `auth.failed` with `reason=missing_admin_key` / `unknown_admin_key` and never the raw key.
  - `AdminApiIT.paddedKeyIsRejected`: `X-Admin-Key: " test-admin-key-shop "` → `401` (exact compare; Review Focus 5).
  - `AdminApiIT.senderKeyIsNotAnAdminKey`: `X-Api-Key`-valid sender key sent as `X-Admin-Key` → `401`.
  - `AdminApiIT.postIs405`: `POST /v1/admin/me` → `405` problem.
  - `AdminApiIT.pageIsServedWithHeaders`: `GET /admin/` and `/admin` → `200` `text/html` (forwarded to `/admin/index.html`, a placeholder file in this task), headers exactly §5 (CSP string, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`).
  - `AdminRateLimitIT.thirdCallIs429`: two `200`, third `429` with `Retry-After`, log `rate_limit.exceeded` with `rate_limit.scope=admin`, `rp.admin_key=admin-limited`.
  - `AdminDisabledIT` (no `reliable-push.admin.keys`): `GET /v1/admin/me` with any key and `GET /admin/` → `404` with the standard not-found problem body.
- [ ] **Step 2: Run** those ITs — expected: FAIL (404 for everything / classes missing).
- [ ] **Step 3: Implement.** `AdminKeyFilter` (order `HIGHEST_PRECEDENCE + 22`, path prefix `/v1/admin`, `shouldNotFilter` outside it): disabled → `404` problem; missing/unknown → `401` as above; then rate limit on bucket `admin:<keyId>`; sets `AdminPrincipal.ATTR` and `rp.app_id` is **not** set (multi-app). `AdminPageFilter` (prefix `/admin`): disabled → `404`; else sets the §5 headers and forwards `/admin` and `/admin/` to `/admin/index.html`. `AdminKeyFilter` sets `Cache-Control: no-store` on the response before anything else, so it covers successes and every error (filter-written or controller-written). `AdminController` is `@RequestMapping("/v1/admin")` with `@GetMapping("/me")`.
- [ ] **Step 4: Run** the ITs, then `./mvnw -B verify` — expected: PASS / BUILD SUCCESS.
- [ ] **Step 5: Commit** `feat(backend): admin key filter, /v1/admin/me and the admin page gate`.

### Task 4: Search, detail, payload, devices

**Files:**
- Create: `backend/src/main/java/io/reliablepush/admin/AdminQueries.java`, `AdminViews.java` (response records), `AdminAudit.java`
- Modify: `AdminController.java`; `backend/src/main/java/io/reliablepush/notifications/PageCursor.java` (make the type and `encode`/`decode` public; no format change); `openapi.yaml` (four paths of §4); `docs/operations.md` (events `admin.search`, `admin.notification_viewed`, `admin.payload_viewed`)
- Test: `backend/src/test/java/io/reliablepush/admin/AdminSearchIT.java`

**Interfaces:**
- Consumes: `AdminPrincipal`; `FallbackAttemptRepository.forNotification` (Task 2); `UserHash.of`, `EventLog.info`.
- Produces: routes `GET /v1/admin/notifications`, `/notifications/{trackingId}`, `/notifications/{trackingId}/payload`, `/devices` with exactly the §4 fields; `AdminAudit.search(AdminPrincipal, String appId, Set<String> filterNames)`, `viewed(AdminPrincipal, String appId, UUID id, String userId)`, `payloadViewed(...)` (same args).

- [ ] **Step 1: Write the failing tests** in `AdminSearchIT` (seed rows through the sender and device APIs or direct SQL; both apps `shop`, `other`):
  - `searchByUserNewestFirstWithCursor`: 3 notifications of user `u1` → `limit=2` gives 2 items newest first and a `nextCursor`; the next page gives 1 item and `nextCursor=null`.
  - `searchFilters`: `externalId`, `state=FALLBACK_FAILED`, `from`/`to` each narrow the result; `state=nope` → `400` field `state`; `limit=0` and `limit=101` → `400`; `from=to` → `400` (Review Focus 2).
  - `oddUserIdIsFound`: user `"a/b c<&>é"` (URL-encoded in the query) → found; JSON returns it unchanged (Review Focus 1).
  - `appRequiredAndScoped`: no `app` → `400`; `admin-shop` with `app=other` → `404`; `admin-shop` on an `other` tracking id → `404`; malformed id → `404`.
  - `detailHasEventsAndAttempts`: a notification delivered on iOS then fallen back with 503→200 → `events=[{type:DELIVERED, platform:IOS, late:false,...}]`, `attempts` of 2 rows as in Task 2, `payloadBytes` > 0, no `payload` field.
  - `payloadNeedsPermission`: `admin-shop` → `404` (same body as unknown); `admin-all` → `200` with the stored JSON.
  - `devicesOfUser`: two devices, one revoked → both, newest first, `revokedAt` set on one; missing `userId` → `400`.
  - `auditEvents`: the captured log has `admin.search` with `filters` = names only (assert the raw `userId` string is absent from every log line), `admin.notification_viewed` and `admin.payload_viewed` with `rp.admin_key`, `rp.tracking_id`, `rp.user_hash`.
  - every `200`/`400`/`404` response contract-checked.
- [ ] **Step 2: Run** `AdminSearchIT` — expected: FAIL (404 for unknown routes).
- [ ] **Step 3: Implement.** `AdminQueries` uses `JdbcClient`; search keyset on `(created_at, id)` desc with the existing `PageCursor`; `limit` default 50; range validation `from < to`. Platform comes from `device.platform` joined on `notification_event.device_id` (null when purged). `payloadBytes` = `octet_length(fallback_payload::text)`.
- [ ] **Step 4: Run** the IT, then `./mvnw -B verify` — expected: PASS / BUILD SUCCESS.
- [ ] **Step 5: Commit** `feat(backend): admin search, notification detail, payload and devices`.

### Task 5: Overview

**Files:**
- Create: `backend/src/main/java/io/reliablepush/admin/Rates.java`, `OverviewQuery.java`
- Modify: `AdminController.java`, `openapi.yaml` (`/v1/admin/overview`)
- Test: `backend/src/test/java/io/reliablepush/admin/RatesTest.java`, `AdminOverviewIT.java`

**Interfaces:**
- Produces: `record Counts(int registered, int cancelled, int delivered, int read, int fallbackTriggered, int fallbackFailed, int fallbackExpired)`; `static Double Rates.of(int part, Counts c)` (`null` when `registered - cancelled == 0`); `GET /v1/admin/overview?app=&window=` per §4, apps sorted by id. Task 6 reuses `Counts` and `Rates`.

- [ ] **Step 1: Write the failing tests:**
  - `RatesTest`: `of(3, counts(registered 10, cancelled 2))` = `0.375`; `n = 0` → `null`.
  - `AdminOverviewIT.countsAndRatesInWindow`: in `shop`, 4 registered in the last hour (1 cancelled, 2 delivered, 1 read, 1 fallback triggered) and 1 registered 2 hours ago → `window=1h` counts exactly those 4 rows; `deliveryRate = 2/3`.
  - `AdminOverviewIT.backlogNow`: one `PENDING` not due, one due and unclaimed (worker paused: profile `api,test` only), one `DISPATCHING` → `pending=1, dueUnclaimed=1, dispatching=1`, `oldestDueAgeSeconds >= 0`; with none due → `oldestDueAgeSeconds=null`.
  - `AdminOverviewIT.emptyAppIsZeros`: `other` with no rows → all counts 0, all rates `null` (Review Focus 3).
  - `AdminOverviewIT.windowValidation`: `window=2h` → `400`; default window is `24h`; `admin-shop` sees only `shop`; `app=other` for it → `404`.
- [ ] **Step 2: Run** — expected: FAIL.
- [ ] **Step 3: Implement** one grouped `count(*) filter (where ...)` query per window over `notification (app_id, created_at)` and one backlog query (§4 definitions verbatim).
- [ ] **Step 4: Run**, then `./mvnw -B verify` — expected: PASS / BUILD SUCCESS.
- [ ] **Step 5: Commit** `feat(backend): admin overview with rates and backlog`.

### Task 6: Daily rollups, retention guard, trends

**Files:**
- Create: `backend/src/main/java/io/reliablepush/admin/DailyStatsRepository.java`, `DailyRollupJob.java`, `DailyStats.java`, `RollupWindow.java`
- Modify: `backend/src/main/java/io/reliablepush/notifications/RetentionJob.java` (guard), `AdminController.java` (`/stats/daily`), `openapi.yaml`, `backend/src/test/resources/application-test.yml` (`reliable-push.admin.rollup.cron: "-"`), `docs/operations.md` (events `rollup.completed`, `rollup.failed`, `retention.skipped`; config keys of §2; retention ordering)
- Test: `backend/src/test/java/io/reliablepush/admin/RollupWindowTest.java`, `DailyRollupIT.java`

**Interfaces:**
- Consumes: `Counts`, `Rates` (Task 5); `ReliablePushProperties.retentionDays()`; `AdminKeys.enabled()`.
- Produces: `DailyRollupJob.run()` returns the number of days written; `static List<LocalDate> RollupWindow.days(LocalDate today, int retentionDays)` (= `today-W .. today-1`, `W = min(7, retentionDays)`); `DailyStatsRepository.upsert(LocalDate day)` (all configured apps, zero rows included), `deleteBefore(LocalDate)`, `Optional<Instant> lastComputedAt()`, `List<DailyStats> range(String appId, LocalDate from, LocalDate toExclusive)`; `GET /v1/admin/stats/daily`.

- [ ] **Step 1: Write the failing tests:**
  - `RollupWindowTest`: today `2026-11-06`, retention 30 → `2026-10-30 .. 2026-11-05` (7 days); retention 1 → `[2026-11-05]`; retention 7 → 7 days.
  - `DailyRollupIT.upsertsWindowWithZeroRows`: rows on two days for `shop` → `run()` writes a row per configured app per window day; `other` rows are all zeros; a second `run()` changes `computed_at` only.
  - `DailyRollupIT.dayBoundary`: created `2026-11-04T23:59:59.999Z` and `2026-11-05T00:00:00Z` → one in each day (Review Focus 4).
  - `DailyRollupIT.countsMatchDefinitions`: a READ-only notification counts in `delivered` and `read`; platform from the READ device; `delivery_p50_ms`/`p95_ms` from `percentile_cont`, `null` for a day without deliveries.
  - `DailyRollupIT.lockMakesSecondRunANoop`: hold `pg_advisory_xact_lock(<const>)` in another transaction → `run()` returns 0 and logs nothing written.
  - `DailyRollupIT.oldRollupsDeleted`: a row older than 400 days is deleted by `run()`.
  - `DailyRollupIT.retentionSkipsWithoutRecentRollup`: admin enabled, rollup cron set to a real expression via `@TestPropertySource`, `daily_stats` empty → `RetentionJob.purge()` deletes nothing and logs WARN `retention.skipped reason=rollup_missing`; after `run()` it purges. With admin disabled (`AdminDisabledIT` context) it purges as today.
  - `DailyRollupIT.trendsRoute`: `/stats/daily?app=shop&from=..&to=..` returns stored days with `live=false` and window days with `live=true`; span 401 days → `400`; `from >= to` → `400`; contract-checked.
- [ ] **Step 2: Run** — expected: FAIL.
- [ ] **Step 3: Implement.** `DailyRollupJob` is `@Component @Profile("worker")`, `@Scheduled(cron = "${reliable-push.admin.rollup.cron:0 7 3 * * *}", zone = "UTC")`, does nothing when `!adminKeys.enabled()`; runs in one `TransactionTemplate` transaction starting with `pg_try_advisory_xact_lock(4_242_001)`; logs `rollup.completed count=<days>` or WARN `rollup.failed reason=<exception simple name>`. `RetentionJob.purge()` applies the guard of §3.2 (20 hours) only when admin is enabled and the rollup cron is not `-`.
- [ ] **Step 4: Run**, then `./mvnw -B verify` — expected: PASS / BUILD SUCCESS.
- [ ] **Step 5: Commit** `feat(backend): daily rollups, retention guard and admin trends`.

### Task 7: The admin page

**Files:**
- Create: `backend/src/main/resources/static/admin/index.html`, `admin.css`, `js/main.js` (router), `js/api.js` (fetch wrapper: `X-Admin-Key`, 401 → sign-in, 429 → wait `Retry-After`, 503/network → banner), `js/dom.js` (element builder using `textContent` only), `js/views/signin.js`, `overview.js`, `trends.js` (SVG charts), `search.js`, `notification.js`, `devices.js`
- Test: `backend/src/test/java/io/reliablepush/admin/StaticAdminPageTest.java`

**Interfaces:**
- Consumes: the API of Tasks 3-6 exactly as in §4.
- Produces: hash routes `#/overview`, `#/trends`, `#/search`, `#/n/<trackingId>`, `#/devices/<app>/<userId>` (segments via `encodeURIComponent`); stable element ids the smoke test uses: `#key`, `#signin`, `#signout`, `#app`, `#window`, `#tiles`, `#backlog`, `#search-user`, `#search-go`, `#results`, `#timeline`, `#show-payload`, `#payload`.

- [ ] **Step 1: Write the failing test** `StaticAdminPageTest` (plain JUnit over the classpath files):
  - `noInlineScriptOrStyle`: `index.html` has no `<script>` without `src`, no `style=` attribute, no `on*=` handler.
  - `noInnerHtml`: no `.js` file contains `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write` or `eval(`.
  - `noRemoteReferences`: no file references `http://` or `https://` (except the SVG namespace `http://www.w3.org/2000/svg`).
  - `keyOnlyInSessionStorage`: `localStorage` appears nowhere; `sessionStorage` appears only in `api.js`; the sign-in code calls `.trim()` on the key input (Review Focus 5).
  - `everyRouteHasAView`: `main.js` maps the five route prefixes above.
- [ ] **Step 2: Run** `./mvnw -B test -Dtest=StaticAdminPageTest` — expected: FAIL (files missing).
- [ ] **Step 3: Implement** the page per §5 (light/dark via `prefers-color-scheme`, layout usable at 360 px, overview refresh every 30 s with a pause toggle, amber backlog when `oldestDueAgeSeconds > 60`, trends ranges 30/90/400 with `live` days marked, search by `trackingId` opens `#/n/<id>` directly, "Show payload" only when `/me` lists `payload`, otherwise "hidden (N bytes)").
- [ ] **Step 4: Run** the test, then `./mvnw -B verify`; start the stack (`./scripts/demo.sh`, after Task 8 configures the key) or a local backend and open `/admin/` in the browser pane: sign in, every view renders in light and dark, no console error.
- [ ] **Step 5: Commit** `feat(backend): admin dashboard page`.

### Task 8: Helm, harness, E2E scenario and smoke

**Files:**
- Modify: `deploy/helm/reliable-push/values.yaml` (`config.admin: {}` with a commented example), `deploy/helm/reliable-push/tests/configmap_test.yaml` (admin block renders under `reliable-push.admin`); `harness/compose/GenSecrets.java` (one raw admin key `RP_ADMIN_KEY_E2E` for the harness as `HARNESS_ADMINKEY`, its SHA-256 as `RELIABLEPUSH_ADMIN_KEYS_0_KEYSHA256`), `harness/compose/backend.env.template` (`RELIABLEPUSH_ADMIN_KEYS_0_ID=e2e`, `..._APPS_0=*`, `..._PERMISSIONS_0=payload`); `harness/src/main/java/io/reliablepush/harness/config/HarnessProperties.java` (`String adminKey`, redacted in `toString`); `harness/src/main/resources/application.yml` (document `HARNESS_ADMINKEY`)
- Create: `harness/src/main/java/io/reliablepush/harness/admin/AdminApiClient.java` (JDK `HttpClient` + Jackson, like `FakeSender`), `harness/src/main/java/io/reliablepush/harness/scenarios/catalog/AdminDashboard.java` (`@Order(17)`, name `admin-dashboard`), `harness/src/test/java/io/reliablepush/harness/console/AdminSmokeE2E.java`
- Test: `harness/src/test/java/io/reliablepush/harness/admin/AdminApiClientTest.java` (against a JDK `HttpServer` stub), `HarnessPropertiesTest` (redaction), the E2E itself

**Interfaces:**
- Produces: `AdminApiClient.overview(String app, String window)`, `search(String app, String userId)`, `detail(UUID trackingId)` returning Jackson `JsonNode`; the `admin-dashboard` scenario.

- [ ] **Step 1: Write the failing tests:** `AdminApiClientTest` (sends `X-Admin-Key`, parses the three responses, maps `404` to `Optional.empty()` for `detail`); `HarnessPropertiesTest.adminKeyRedacted`; helm-unittest case `admin keys render` (`config.admin.keys[0].id: ops` → configmap contains `admin:` / `id: ops`).
- [ ] **Step 2: Run** `./mvnw -B -pl harness -am verify` and `deploy/helm/scripts/validate.sh` — expected: FAIL.
- [ ] **Step 3: Implement** the client, properties, compose secret, chart value and the scenario: in `shop`, user `ctx.userId(1)` with one iOS device: notification A delivered (device `"immediate"`), notification B never confirmed with the receiver in `FAIL_503` once then OK; await `FALLBACK_TRIGGERED` for B; then `ctx.check` that `search(shop, user)` lists A and B, `detail(B)` has 2 attempts (`RETRYABLE` 503, `SUCCESS` 200) and `detail(A)` one `DELIVERED` event with platform `IOS`, and `overview(shop, "1h")` has `registered >= 2`, `delivered >= 1`, `fallbackTriggered >= 1`. `AdminSmokeE2E` (same Playwright setup as `ConsoleSmokeE2E`, run by the `console-smoke` profile): sign in with the key padded by spaces, see the overview tiles, search the scenario user (with a `/` and a space in its id) and open the detail timeline, see "Show payload".
- [ ] **Step 4: Run** `./mvnw -B -pl harness -am verify`, `deploy/helm/scripts/validate.sh`, `./scripts/e2e.sh` — expected: `E2E: 18/18 passed`; `./scripts/ios-e2e.sh` and `./scripts/android-e2e.sh` unchanged (`8/8 passed, 1 skipped`), run once to confirm the compose change.
- [ ] **Step 5: Commit** `test(harness): admin-dashboard scenario, admin smoke test and compose admin key` and `feat(helm): admin keys in values`.

### Task 9: Documentation, diagrams, slides, requirements

**Files:**
- Create: `docs/admin.md`
- Modify: `docs/requirements.md` (FR-16, FR-17 verbatim from §9; drop "admin UI" from "Out of scope" leaving "full multi-tenant SaaS isolation"), `docs/specs/2026-11-03-reliable-push-design.md` (a short "Cycle 4" note linking the dashboard spec), `docs/api.md`, `docs/operations.md` (check all events and keys of Tasks 3-6 are there), `docs/security.md` (admin key generation with `openssl rand -base64 32` + `printf %s "$KEY" | shasum -a 256`, rotation, audit, page headers), `docs/architecture.md`, `docs/deployment.md` (Helm `config.admin`, restricting `/admin/` and `/v1/admin/` at the Ingress/Route), `docs/testing.md` (scenario count 18, admin smoke), `docs/overview.md` (new section "Ops dashboard" with a Mermaid flowchart: browser → `/admin/` + `/v1/admin` → PostgreSQL; worker → `fallback_attempt` and `daily_stats`; renumber later sections), `docs/images/reliable-push-at-a-glance.svg` + re-rendered `.png` (a "Dashboard /admin" element inside the reliable-push backend box), `docs/presentation.html` (new slide "Ops dashboard" after the backend slide; status slide lists the dashboard as on `develop`), `README.md` (feature list and doc links), `CHANGELOG.md` (`Unreleased` → Added), `docs/TODO.md` (5.1 says 0.2.0 = dashboard), `docs/STATUS.md`, `docs/getting-started.md` (try the dashboard in the demo)
- Test: none (docs); verification below

- [ ] **Step 1:** Write `docs/admin.md` (sign-in, each view, keys and permissions, audit, what is not possible: read-only) and the other edits above.
- [ ] **Step 2:** Re-render the PNG: headless Chrome on a scratch `render.html` that shows the SVG at 1600x1100, `--force-device-scale-factor=2` → `docs/images/reliable-push-at-a-glance.png` 3200x2200; open both the presentation and the PNG and check nothing overflows.
- [ ] **Step 3: Verify:** `grep -rn "admin" docs/requirements.md docs/api.md docs/operations.md docs/security.md` lists the new sections; every new log event of Tasks 3-6 appears in the `operations.md` table; `./scripts/e2e.sh --scenario log-catalogue` passes; Mermaid in `overview.md` renders in the browser pane.
- [ ] **Step 4: Commit** `docs: ops dashboard guide, diagrams, slides and requirements`.

### Finish

- [ ] Run the `review-loop` skill on `git diff develop...HEAD` (correctness, security, tests, no mobile policy role: no SDK change); fix high/medium findings test-first.
- [ ] Full check: `cd backend && ./mvnw -B verify -Pimage`, `./mvnw -B verify` (root), `deploy/helm/scripts/validate.sh`, `./scripts/e2e.sh` (`E2E: 18/18 passed`).
- [ ] Merge `feature/ops-dashboard` into `develop` with `--no-ff` (E2E result in the merge message), push, check CI. The 0.2.0 release is a separate maintainer decision.
