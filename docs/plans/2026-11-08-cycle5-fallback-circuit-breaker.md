# Fallback Circuit Breaker (cycle 5, 0.2.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A per-app circuit breaker that stops fallback webhook calls during a massive lack of `DELIVERED` tracking,
closes by itself when tracking returns, can be forced closed from the admin console, and keeps a stop-and-go history.

**Architecture:** New package `io.reliablepush.fallback.circuit` (properties, repository, pure transition function,
detector, suppressor). `FallbackScheduler.tick()` calls the detector, then the suppressor, then claims with the
suppressed apps excluded. One Flyway migration `V6__fallback_circuit.sql`. The admin module gains three routes, the
`fallback-control` permission and page elements.

**Tech Stack:** Java 21, Spring Boot 4 (JdbcClient, Flyway, scheduling), PostgreSQL 16, Log4j 2 ECS, JUnit 5 +
AssertJ + Testcontainers + `OpenApiAssert`, plain HTML/CSS/JS ES modules, Playwright (harness smoke), Helm.

**Spec:** [`docs/specs/2026-11-07-fallback-circuit-breaker-design.md`](../specs/2026-11-07-fallback-circuit-breaker-design.md)
(approved 2026-11-08). `§n` below refers to it.

## Global Constraints

- Branch `feature/fallback-circuit-breaker`; Conventional Commits; trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Environment: `JAVA_HOME=/opt/homebrew/opt/openjdk@21`, its `bin` first on `PATH`, `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- Backend check at the end of every backend task: `cd backend && ./mvnw -B verify` (unit + IT + 80 % line coverage gate).
- No new runtime dependency; no ORM; `JdbcClient` only.
- Config prefix `reliable-push.fallback`: `suspended: false`; `circuit-breaker`: `enabled: true`, `window: 5m`, `min-due: 50`, `max-delivered-ratio: 0.10`, `close-min-delivered: 10`, `min-open: 1m`, `force-hold: 60m` (§2). Bare numbers are minutes.
- Advisory lock key `4242002` (`pg_try_advisory_xact_lock`). Cohort and resumed reads cap at 1,000 rows; suppression chunks of 1,000 rows (§4, §5).
- Log events (exact names): `fallback.circuit_opened` (WARN), `fallback.circuit_closed` (INFO), `fallback.circuit_forced` (WARN), `fallback.suppressed` (WARN, one per tick and app), `admin.fallback_resumed` (audit). Every new event goes into the event table of `docs/operations.md` in the task that introduces it (harness `log-catalogue` scenario).
- Metric `rp_fallback_suppressed_total{app,reason}` with `reason` = `circuit_open` | `fallback_suspended`; suppressed rows also count in `rp_fallback_total{outcome="expired"}`.
- No backward compatibility is needed (nothing is deployed): change existing code freely.
- Logs never carry keys, `userId`s or payloads. Every admin response is in `openapi.yaml` and checked with `OpenApiAssert.assertMatchesContract`.
- English everywhere; docs-sync rule applies (Task 7 does the cross-cutting docs).

## Review Focus

1. An app with fewer than `min-due` fallbacks in the window (a small app) → never opens, however bad its ratio (Task 3 unit + IT).
2. A campaign followed by silence with 30 % offline users → the circuit stays closed (Task 3 IT).
3. Two workers ticking at once → one transition and one event row, never two (Task 3 IT).
4. A row under a live lease while its app's circuit opens → left to its worker, not suppressed (Task 4 IT).
5. `suspended: true` with a `FORCED` app → still suppressed; resume answers `409` (Task 4 and Task 5 ITs).

---

### Task 1: Configuration

**Files:**
- Create: `backend/src/main/java/io/reliablepush/fallback/circuit/CircuitBreakerProperties.java`
- Modify: `backend/src/main/java/io/reliablepush/fallback/WorkerConfig.java` (bind and expose the bean), `backend/src/main/resources/application.yml` (defaults, commented)
- Test: `backend/src/test/java/io/reliablepush/fallback/circuit/CircuitBreakerPropertiesTest.java`

**Interfaces:**
- Produces: `record CircuitBreakerProperties(boolean suspended, boolean enabled, Duration window, int minDue, double maxDeliveredRatio, int closeMinDelivered, Duration minOpen, Duration forceHold)` with `static CircuitBreakerProperties defaults()` and a compact constructor that validates.

- [ ] **Step 1: Write the failing tests** — `defaultsMatchSpec` (all §2 values); `bareNumbersAreMinutes` (`"5"` → 5 min, through the binder used by `WorkerConfig`); `rejectsInvalid` parameterized: `window` 0, `min-open` −1, `force-hold` 0, `min-due` 0, `close-min-delivered` 0, `max-delivered-ratio` 0 and 1.01 → `IllegalStateException` naming the key; ratio `1.0` accepted.
- [ ] **Step 2: Run** `./mvnw -B test -Dtest=CircuitBreakerPropertiesTest` — FAIL (class missing).
- [ ] **Step 3: Implement** the record and the binding in `WorkerConfig` (reuse its existing minutes conversion for bare numbers).
- [ ] **Step 4: Run** the test — PASS; then `./mvnw -B verify`.
- [ ] **Step 5: Commit** `feat(backend): fallback circuit breaker configuration`.

### Task 2: Schema and circuit repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__fallback_circuit.sql` (exact DDL of §3), `backend/src/main/java/io/reliablepush/fallback/circuit/CircuitState.java` (enum `CLOSED, OPEN, FORCED`), `Circuit.java`, `CircuitEvent.java`, `CircuitRepository.java`
- Modify: `backend/src/main/java/io/reliablepush/admin/DailyRollupJob.java` or its retention step (delete `fallback_circuit_event` rows older than `admin.rollup-retention-days`, same schedule as `daily_stats`)
- Test: `backend/src/test/java/io/reliablepush/fallback/circuit/CircuitRepositoryIT.java`

**Interfaces:**
- Produces:
  - `record Circuit(String appId, CircuitState state, Instant since, Instant forcedUntil, String forcedBy, long suppressed)`; `Circuit.closed(String appId, Instant epoch)` for apps without a row.
  - `record CircuitEvent(long id, String appId, Instant at, String transition, String reason, Long due, BigDecimal ratio, Long suppressed, String adminKeyId)`.
  - `CircuitRepository`:
    - `Map<String, Circuit> findAll(Collection<String> appIds)`
    - `Optional<Circuit> find(String appId)`
    - `void transition(Circuit next, CircuitEvent event)` (upsert row + insert event, same transaction)
    - `void addSuppressed(String appId, long count)`
    - `List<CircuitEvent> events(String appId, int limit, Long beforeId)` (newest first, keyset on `id`)
    - `int deleteEventsBefore(Instant cutoff)`

- [ ] **Step 1: Write the failing IT** — `missingRowReadsClosed`; `transitionUpsertsAndAppendsEvent` (OPEN then CLOSED: row state, `since`, two events newest first); `addSuppressedAccumulates`; `eventsPageByCursor` (limit 2 of 3, cursor returns the third); `retentionDeletesOldEvents`.
- [ ] **Step 2: Run** `./mvnw -B verify -Dit.test=CircuitRepositoryIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` — FAIL.
- [ ] **Step 3: Implement** migration and repository.
- [ ] **Step 4: Run** the IT — PASS; then `./mvnw -B verify`.
- [ ] **Step 5: Commit** `feat(backend): fallback circuit schema and repository`.

### Task 3: Detection

**Files:**
- Create: `backend/src/main/java/io/reliablepush/fallback/circuit/CircuitTransitions.java` (pure), `DetectionQueries.java`, `CircuitBreaker.java`
- Test: `backend/src/test/java/io/reliablepush/fallback/circuit/CircuitTransitionsTest.java`, `backend/src/test/java/io/reliablepush/fallback/circuit/CircuitBreakerIT.java`

**Interfaces:**
- Consumes: Task 1 properties, Task 2 repository.
- Produces:
  - `record Observation(long due, Double ratio, boolean resumed)` (`ratio` null when `due < minDue`).
  - `CircuitTransitions.next(Circuit current, Observation obs, Instant now, CircuitBreakerProperties p) -> Optional<Transition>` where `record Transition(Circuit next, String transition, String reason)`.
  - `DetectionQueries`:
    - `long due(String appId, Instant now, Duration window, int cap)`
    - `Double cohortRatio(String appId, Instant now, Duration window, int minDue)`
    - `boolean resumed(String appId, Instant since, int closeMinDelivered)`
  - `CircuitBreaker.evaluate(Collection<String> appIds) -> Map<String, Circuit>`: under the advisory lock it evaluates and writes transitions; without the lock it only reads. With `enabled=false` it returns every app as `CLOSED` and touches nothing. It also runs the force expiry (`FORCED` → `CLOSED` with `FORCE_ENDED`/`force_expired`).
  - `CircuitBreaker.force(String appId, String adminKeyId) -> Circuit` (used by Task 5).
- Rules of §4 exactly: open when `due >= min-due && ratio < max-delivered-ratio`; close when `now - since >= min-open && resumed`; `FORCED` ends on `forced_until` or resumed; `NOT_READ` rows count as delivered when `delivered_at` is set.

- [ ] **Step 1: Write the failing unit tests** — one test per row of the §4 table plus boundaries: `due = min-due - 1` stays closed; `ratio == max-delivered-ratio` stays closed; `min-open` not elapsed stays open; `forced_until == now` ends; FORCED ignores a bad ratio.
- [ ] **Step 2: Write the failing IT** — `stormOpensBeforeAnyClaim` (60 unmet due rows, none delivered → OPEN, event `OPENED`/`tracking_missing` with `due` and `ratio`); `campaignThenSilenceStaysClosed` (100 sends, 70 delivered, all fallbacks come due later with no new traffic → CLOSED); `outageDuringSendsTrips` (cohort undelivered, new sends delivered → OPEN); `smallAppNeverTrips` (49 unmet); `closesWhenDeliveredResumes` (after `min-open`, 10 new delivered → CLOSED with `suppressed`); `twoWorkersOneTransition` (two threads call `evaluate` concurrently → exactly one event row); `disabledNeverOpens`; `forceHoldExpires`. Log line `fallback.circuit_opened` checked with the existing log-capture helper.
- [ ] **Step 3: Run** both — FAIL.
- [ ] **Step 4: Implement** the three classes (queries on `notification_due`, `notification_closed`, `notification_app_created`; each query `limit`ed as §4).
- [ ] **Step 5: Run** both — PASS; add the three log events to `docs/operations.md`; `./mvnw -B verify`.
- [ ] **Step 6: Commit** `feat(backend): fallback circuit detection`.

### Task 4: Suppression and scheduler integration

**Files:**
- Create: `backend/src/main/java/io/reliablepush/fallback/circuit/Suppressor.java`
- Modify: `FallbackScheduler.java` (tick: evaluate → suppress → claim excluding suppressed apps), `WorkerConfig.java` (wiring), `NotificationRepository.java` (`claimDue(Instant now, Instant leaseUntil, int batch, Set<String> excludedApps)`; `int expireClaimable(String appId, Instant now, int chunk)`), `RpMetrics.java` (`fallbackSuppressed(String appId, String reason, long count)`)
- Test: `backend/src/test/java/io/reliablepush/fallback/circuit/SuppressorIT.java`; update `FallbackSchedulerTest`/`FallbackSchedulerIT` for the new constructor

**Interfaces:**
- Consumes: `CircuitBreaker.evaluate`, `CircuitRepository.addSuppressed`.
- Produces: `Suppressor.suppress(Map<String, Circuit> circuits, boolean suspended, Instant now) -> Set<String>` (the suppressed app ids); `expireClaimable` closes rows as `FALLBACK_EXPIRED` per §5 and skips rows with `lease_until > now`.

- [ ] **Step 1: Write the failing IT** — `openCircuitExpiresBacklogWithoutWebhook` (stub webhook sees 0 calls; rows `FALLBACK_EXPIRED`, `closed_at` set, `due_at`/`next_attempt_at`/`lease_until` null; one `fallback.suppressed` line with `count` and `reason=circuit_open`; circuit `suppressed` grows; metric grows); `suspendedSuppressesEveryAppEvenForced` (`reason=fallback_suspended`); `liveLeaseIsLeftAlone`; `chunksOf1000` (2,500 rows → all expired, three statements); `otherAppsStillFire` (app B closed circuit keeps its webhook call in the same tick); `lateEventAfterSuppressionIsLate` (event stored, state stays `FALLBACK_EXPIRED`).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement**; update the existing scheduler tests to the new wiring.
- [ ] **Step 4: Run** — PASS; add `fallback.suppressed` to `docs/operations.md`; `./mvnw -B verify`.
- [ ] **Step 5: Commit** `feat(backend): suppress fallbacks of open circuits`.

### Task 5: Admin API and permission

**Files:**
- Modify: `AdminKeys.java` (permission `fallback-control`), `AdminPrincipal.java` (`canControlFallback()`), `AdminController.java` (three routes of §6), `AdminAudit.java` (`admin.fallback_resumed`), `backend/src/main/resources/openapi.yaml`
- Test: `backend/src/test/java/io/reliablepush/admin/AdminFallbackCircuitIT.java`; `AdminKeysTest` (new permission parsed, unknown still fails)

**Interfaces:**
- Consumes: `CircuitRepository.findAll/events`, `CircuitBreaker.force`, the properties' `suspended`/`enabled`.
- Produces:
  - `GET /v1/admin/fallback-circuits` → `{suspended, enabled, items:[{appId,state,since,forcedUntil,forcedBy,suppressed}]}`, scoped to the key's apps;
  - `GET /v1/admin/fallback-circuits/{appId}/events?limit=&cursor=` → `{items, nextCursor}` (limit 1..200, default 50);
  - `POST /v1/admin/fallback-circuits/{appId}:resume` → `200` circuit. `403` without the permission, `404` outside the scope, `409` when suspended or disabled.
  - `GET /v1/admin/me` gains `permissions` containing `fallback-control` when granted (the page needs it).

- [ ] **Step 1: Write the failing IT** — list (absent rows as `CLOSED`, scope filter), events paging, resume `200` (state `FORCED`, `forcedUntil = now + 60m`, `forcedBy` = key id, event `FORCED` with `admin_key_id`, audit line without the key), `403`, `404`, `409` suspended, `409` disabled, rate limit applies; every response through `OpenApiAssert.assertMatchesContract`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** routes, permission, audit, OpenAPI.
- [ ] **Step 4: Run** — PASS; add `admin.fallback_resumed` to `docs/operations.md`; `./mvnw -B verify`.
- [ ] **Step 5: Commit** `feat(backend): admin fallback circuit routes`.

### Task 6: Admin page

**Files:**
- Create: `backend/src/main/resources/admin-ui/js/views/circuit.js`
- Modify: `admin-ui/js/views/overview.js` (badge per app, suspended banner), `admin-ui/js/main.js` (route `#/circuit/<appId>`), `admin-ui/js/api.js` (three calls), `admin-ui/admin.css`, `admin-ui/index.html` if needed; `backend/src/test/java/io/reliablepush/admin/StaticAdminPageTest.java`; harness Playwright smoke (`harness`, profile `console-smoke`, the existing admin smoke class)

**Interfaces:**
- Consumes: Task 5 routes and `/me` permissions.

- [ ] **Step 1: Write the failing tests** — the static page test asserts `circuit.js` is served with the same CSP headers. Smoke: overview shows `CLOSED` badges; with a seeded open circuit the badge reads `OPEN` with the suppressed count; history view lists events; the "Resume fallbacks" button is absent without `fallback-control` and, with it, asks for confirmation and then shows `FORCED`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** (no inline script or style, no third-party code; text only through `textContent`).
- [ ] **Step 4: Run** `./mvnw -B verify` in `backend`, then the smoke as documented in `docs/testing.md` — PASS.
- [ ] **Step 5: Commit** `feat(backend): admin page fallback circuit view`.

### Task 7: E2E scenario, Helm, docs, diagrams, presentation

**Files:**
- Create: `harness/src/main/java/io/reliablepush/harness/scenarios/catalog/FallbackCircuit.java` (registered like the other catalog scenarios)
- Modify: the harness stack configuration so the scenario's app uses a small `min-due`/`window` (harness `docker-compose` backend env or app config); `deploy/helm/reliable-push/values.yaml` (`config.fallback` example, commented) and its unit test if values are templated; `docs/operations.md`, `docs/admin.md`, `docs/api.md`, `docs/security.md`, `docs/architecture.md` (module, `V6`), `docs/deployment.md`, `docs/integration-guide.md` (senders see `FALLBACK_EXPIRED` during an open circuit), `docs/testing.md` (scenario, `E2E: 19/19 passed`), `docs/overview.md` (fallback diagram with the breaker before the claim), `docs/images/` at-a-glance SVG and PNG, `docs/presentation.html` (fallback slide, status), `README.md`, `docs/requirements.md` (FR-18, FR-19, FR-16/FR-17 changes per §9), `CHANGELOG.md`, `docs/TODO.md`, `docs/STATUS.md`

- [ ] **Step 1: Write the scenario** per §7: burst of notifications whose devices never confirm → circuit `OPEN`, receiver gets 0 calls, rows `FALLBACK_EXPIRED`; then devices confirm new notifications → `CLOSED` after `min-open`; a later unmet notification fires its webhook.
- [ ] **Step 2: Run** `./scripts/e2e.sh --scenario fallback-circuit`, then the full `./scripts/e2e.sh` — expect `E2E: 19/19 passed`.
- [ ] **Step 3: Update** every doc listed; regenerate the PNG from the SVG as done in earlier cycles (see `docs/STATUS.md` history for the command); `deploy/helm/scripts/validate.sh` passes.
- [ ] **Step 4: Run** `cd backend && ./mvnw -B verify`, root `./mvnw -B verify` (harness, client) — PASS.
- [ ] **Step 5: Commit** `docs: fallback circuit breaker` (scenario commit separate: `test(harness): fallback-circuit scenario`).
