# Cycle 1B — Sender Client, Test Harness, Local E2E and Web Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Non-Claude tools: use `.agents/skills/implement-task`. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A zero-dependency Java sender client, a test harness (fallback receiver, device simulator, fake sender, scenario runner) with a local-only web console, and one-command local E2E (`scripts/e2e.sh`) and demo (`scripts/demo.sh`) runs that humans and AI agents use in their feedback loop.

**Architecture:** `client-java/` is a plain Maven library (JDK 17+, no runtime dependencies). `harness/` is one small Spring Boot 4 app ("harness-control") holding all simulators in memory plus the static console; it talks to the real backend only through its public APIs (sender API via `client-java`, device API, webhook). `harness/compose/docker-compose.yml` runs PostgreSQL, one API-only backend, two worker-only backends and the harness; secrets are generated per run by a one-shot container. Scenarios run inside the harness (shared by console and E2E); worker-crash scenarios are driven by the scripts with `docker compose kill`.

**Tech Stack:** Java 21 (harness), Java 17 bytecode (`client-java`), Spring Boot 4.1.x (same version as backend), Maven wrapper, Docker Compose v2, plain HTML/CSS/ES-module JS, Playwright for Java (test scope only), JUnit 5, AssertJ.

**Spec:** `docs/specs/2026-11-03-reliable-push-design.md` (§4 APIs, §5 webhook signing, §6 payload contract, §9.1 log catalogue, §10 harness scenarios, §10.1 local E2E, §10.2 web console). Requirements: FR-14, FR-15, NFR-6, NFR-8.

**Follow-up:** plan 1C (Helm chart for GKE/OpenShift, Gatling load test, API/worker pool sizing).

## Global Constraints

- E2E and the console are **local only**: never run in CI, never built into the backend image or the Helm chart (spec §10.1, §10.2). CI may build and unit/integration-test `client-java` and `harness`.
- One entry point for humans and agents: `./scripts/e2e.sh [--scenario <name>] [--keep]` and `scripts/e2e.ps1`; demo: `./scripts/demo.sh [--realtime]` and `scripts/demo.ps1`. Exit code `0` = all passed.
- E2E outputs: `harness/build/e2e/summary.json` (array of `{scenario, status, durationMs, failure: {expected, actual, hint} | null, logs}`), JUnit XML in `harness/build/e2e/junit/`, container logs in `harness/build/e2e/logs/`. Deterministic: fixed seeds, no wall-clock assertions beyond the scaled clock.
- Fast clock: backend profile `e2e` (or `demo`) with `RELIABLEPUSH_TESTING_MINUTEDURATION=1s` (1 "minute" = 1 s). `demo --realtime` omits it.
- All published ports bind `127.0.0.1` only: backend API `8080`, harness/console `8090`. The console shows the permanent banner **"Test harness — simulated data, not for production"**.
- Dependency budget: `client-java` — JDK only, zero runtime dependencies, `maven.compiler.release=17`; `harness` — the backend's budget (Spring Boot webmvc, Actuator, Log4j 2, Bean Validation) plus `client-java`; Playwright for Java **test scope only**; console frontend — no build step, no npm, no framework, no CDN.
- Harness apps use identity type `signed-grant` (HS256, secret ≥ 32 bytes, lifetime ≤ 5 min) — OIDC issuers must be https outside localhost, and inside compose the harness is not localhost.
- Backend env var names use the dash-less form `RELIABLEPUSH_…`; harness app ids are lowercase letters/digits only: `shop`, `news`, `limited`.
- No secret is committed: per-run secrets are generated into `harness/build/e2e/secrets.env` (git-ignored). Never log or return through the console API: sender API keys, grant secrets, HMAC secrets, device tokens, raw user ids are allowed in the console (they are simulated) but never in backend logs.
- Packages: `io.reliablepush.client` (client-java); `io.reliablepush.harness.{config,receiver,devices,sender,tracking,logs,scenarios,console}`.
- Gitflow: all tasks commit to `feature/cycle1b-harness` from `develop`, merged `--no-ff` after the final review; Conventional Commits; tick the task's checkboxes in this file in the same commit.
- Test naming: unit `*Test`, integration `*IT` (failsafe). One Maven wrapper at repo root; `client-java`: `./mvnw -B -pl client-java verify`; harness: `./mvnw -B -pl harness -am verify` (both from repo root; backend keeps its own wrapper).

## Review Focus

1. **Backend slow or failing to start** — `e2e.sh` waits at most 120 s for `/actuator/health/readiness` of every backend container and the harness, then fails with a message naming the container and pointing at `harness/build/e2e/logs/`, never hangs. Test in Task 11 (`e2e.sh --self-test-timeout` path, see there).
2. **Port 8080 or 8090 already in use** — scripts fail fast before `compose up` with "port 8080 is in use" instead of a compose error. Test in Task 11.
3. **Harness restarted mid-demo** — the console's notification list is rebuilt from the backend list API (FR-15), not only from harness memory; after a harness restart existing notifications reappear. Test in Task 8.
4. **Two scenario runs at once** (console button + `e2e.sh`) — the runner rejects the second with HTTP 409 and a clear message. Test in Task 10.
5. **Secrets in backend logs during E2E** — the `log-hygiene` scenario scans the backend log file for the run's sender keys, grant secrets, HMAC secrets, device tokens and raw user ids and fails if any appears. Test in Task 10.

---

### Task 1: client-java — JSON codec

**Files:**
- Create: `pom.xml` (root aggregator: packaging pom; no parent; lists only `client-java` until Task 4 adds `harness`), `mvnw`, `mvnw.cmd`, `.mvn/wrapper/*` (root wrapper, copy backend wrapper config), `client-java/pom.xml`, `client-java/src/main/java/io/reliablepush/client/internal/Json.java`
- Test: `client-java/src/test/java/io/reliablepush/client/internal/JsonTest.java`

**Interfaces:**
- Produces: `final class Json` (package `io.reliablepush.client.internal`, not exported in docs) — `static String write(Object value)` for `null`, `String`, `Number`, `Boolean`, `Map<String,?>`, `List<?>` (nested); `static Object read(String json)` returning `null|String|Long|Double|Boolean|LinkedHashMap<String,Object>|ArrayList<Object>`; throws `IllegalArgumentException("invalid JSON at <offset>")` on malformed input; max depth 64.

- [x] **Step 1: Failing tests** — `writesNestedStructures()` (`Map.of("a", List.of(1, true, null), "b", "x\"\n\u0001")` → `{"a":[1,true,null],"b":"x\"\n\u0001"}` with `\u0001` escaped as `\u0001`; key order = insertion order for `LinkedHashMap`); `readsWhatItWrites()` (round-trip of a nested map incl. unicode `é` and surrogate pair 😀); `readsNumbers()` (`1` → `Long 1`, `1.5` → `Double`, `-0`, `1e3` → `Double 1000.0`); `rejectsMalformed()` (`{"a":}`, `[1,]`, `"\x"`, trailing garbage `{} x`, depth 65) each → `IllegalArgumentException` with "invalid JSON"; `rejectsUnsupportedTypes()` (`write(new Object())` → IAE).
- [x] **Step 2: Run** `./mvnw -B -pl client-java test` → FAIL (no class).
- [x] **Step 3: Implement** (recursive-descent reader, `StringBuilder` writer; no reflection). pom: `groupId io.reliablepush`, `artifactId reliable-push-client`, version `0.1.0-SNAPSHOT`, `maven.compiler.release 17`, test deps JUnit 5 + AssertJ only, surefire + failsafe, JaCoCo line gate 0.80; **no** `<dependencies>` in compile/runtime scope (enforce with maven-enforcer `banDependencies`… or a test asserting `mvn dependency:list -DincludeScope=runtime` is empty — use maven-enforcer-plugin rule `bannedDependencies` excluding `*:*` for scopes compile/runtime).
- [x] **Step 4: Run** `./mvnw -B -pl client-java verify` → PASS.
- [x] **Step 5: Commit** `feat(client): zero-dependency json codec`

### Task 2: client-java — SenderClient

**Files:**
- Create: `client-java/src/main/java/io/reliablepush/client/{SenderClient,SenderClientBuilder,RegisterRequest,Notification,NotificationPage,NotificationState,FallbackPolicy,ReliablePushException,ProblemDetails}.java`, `client-java/README.md`
- Test: `client-java/src/test/java/io/reliablepush/client/SenderClientTest.java` (JDK `com.sun.net.httpserver.HttpServer` as fake backend — no WireMock)

**Interfaces:**
- Consumes: `Json` (Task 1); backend sender API (`backend/src/main/resources/openapi.yaml`, paths `/v1/notifications`, `/v1/notifications/{trackingId}`).
- Produces:
  - `SenderClient.builder().baseUrl(URI).apiKey(String).timeout(Duration /*default 10s*/).build()`; immutable, thread-safe, one shared JDK `HttpClient` (redirects NEVER).
  - `Notification register(RegisterRequest r)`; `Optional<Notification> get(UUID trackingId)` (404 → empty); `NotificationPage list(String userId /*nullable*/, NotificationState state /*nullable*/, Instant since /*nullable*/, int limit, String cursor /*nullable*/)`; `void cancel(UUID trackingId)`.
  - `record RegisterRequest(String userId, String externalId, FallbackPolicy policy, Integer timeoutMinutes, Map<String, ?> fallback)` with `static Builder builder()`; nullables: externalId, policy, timeoutMinutes.
  - `record Notification(UUID trackingId, NotificationState state, String userId, String externalId, FallbackPolicy policy, int timeoutMinutes, Instant createdAt, Instant dueAt, Instant deliveredAt, Instant readAt, Instant closedAt)`; `record NotificationPage(List<Notification> items, String nextCursor)`.
  - `enum NotificationState { PENDING, DELIVERED, READ, FALLBACK_PENDING, FALLBACK_TRIGGERED, FALLBACK_FAILED, FALLBACK_EXPIRED, CANCELLED }` (external names); `enum FallbackPolicy { NOT_DELIVERED, NOT_READ }`.
  - `class ReliablePushException extends RuntimeException` with `int status()`, `Optional<ProblemDetails> problem()`, `Optional<Duration> retryAfter()`; `record ProblemDetails(String type, String title, int status, String detail, String field)`; IO/timeout → `ReliablePushException` with status `-1`. `toString()`/messages never contain the API key.
- [x] **Step 1: Failing tests** — `registerSendsApiKeyAndBody()` (fake server asserts `X-Api-Key`, `Content-Type: application/json`, body fields; returns 201 → mapped `Notification`); `getUnknownIsEmpty()` (404); `listPassesQueryParams()` (`userId`, `state=FALLBACK_PENDING`, `since` ISO-8601 percent-encoded, `limit`, `cursor`); `cancelConflictThrows409()`; `problemDetailsParsed()` (422 with `field`); `rateLimitedExposesRetryAfter()` (429 + `Retry-After: 7` → `retryAfter()` = 7 s); `timeoutIsStatusMinusOne()` (server sleeps 2 s, timeout 300 ms, returns < 1.5 s); `redirectNotFollowed()` (302 → exception status 302); `apiKeyNeverInExceptionText()`; `unknownJsonFieldsIgnored()`.
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** README: install, usage snippet (register → put `trackingId` as `rp_tid` in the push payload, spec §6), error handling. **Step 4: Run** `./mvnw -B -pl client-java verify` → PASS.
- [x] **Step 5: Commit** `feat(client): sender api client`

### Task 3: Backend — JSON log file for e2e/demo

**Files:**
- Modify: `backend/src/main/resources/log4j2-spring.xml`
- Test: `backend/src/test/java/io/reliablepush/E2eLogFileIT.java`

**Interfaces:**
- Produces: in profiles `e2e | demo` an extra appender writes the same ECS JSON lines to `${env:RP_LOG_FILE:-/tmp/rp-logs/backend.json}` (append, no rotation; stdout unchanged). The harness (Task 9) tails this file from a shared volume.
- [x] **Step 1: Failing test** `E2eLogFileIT` (profiles `test,e2e`, `RP_LOG_FILE` set to a `@TempDir` file via system property/env equivalent supported by Log4j lookup — use `${sys:rp.log.file:-${env:RP_LOG_FILE:-/tmp/rp-logs/backend.json}}`): after GET `/actuator/health` the file contains a JSON line with `event.action`=`api.request`; `notWrittenWithoutProfile()` (profile `test` only → file absent).
- [x] **Step 2: Run** `cd backend && ./mvnw -B verify -Dit.test=E2eLogFileIT` → FAIL. **Step 3: Implement** a `<SpringProfile name="e2e | demo">` `File` appender with the same `JsonTemplateLayout`, referenced from Root only inside that profile (use `<SpringProfile>` around an `AppenderRef` or a second `Root` definition per Log4j/Spring docs). **Step 4: Run** full `./mvnw -B verify` → PASS. Update `docs/operations.md` (profiles table).
- [x] **Step 5: Commit** `feat(backend): json log file in e2e and demo profiles`

### Task 4: Harness skeleton, configuration and container

**Files:**
- Create: `harness/pom.xml` (Spring Boot 4.1.x parent same version as backend; deps: webmvc, actuator, validation, log4j2 (Logback excluded), `io.reliablepush:reliable-push-client`; test: spring-boot-starter-test, `com.microsoft.playwright:playwright` **test scope**), root `pom.xml` (modify: add module `harness`), `harness/src/main/java/io/reliablepush/harness/HarnessApplication.java`, `harness/src/main/java/io/reliablepush/harness/config/{HarnessProperties,HarnessApp}.java`, `harness/src/main/resources/{application.yml,log4j2-spring.xml}`, `harness/Dockerfile` (build context = repo root: builds `client-java` then `harness`; runtime `eclipse-temurin:21.0.13_9-jre-alpine-3.24`; USER 1001), `harness/.dockerignore`
- Test: `harness/src/test/java/io/reliablepush/harness/config/HarnessPropertiesTest.java`, `harness/src/test/java/io/reliablepush/harness/HarnessStartsIT.java`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("harness") record HarnessProperties(URI backendUrl, Path backendLogDir, Map<String, HarnessApp> apps)`; `record HarnessApp(String senderApiKey, String grantSecret, String hmacSecret, String grantIssuer)`; `HarnessProperties.app(String appId)` → `HarnessApp` or `IllegalArgumentException`. `toString()` of both records redacts all secrets.
  - Server port `8090`; Actuator `health` exposed; console static root `/` → `classpath:/static/console/`.
- [x] **Step 1: Failing tests** — `HarnessPropertiesTest.secretsRedactedInToString()`, `.unknownAppThrows()`; `HarnessStartsIT.healthUp()` (random port, test apps from `src/test/resources/application-test.yml` with generated values).
- [x] **Step 2: Run** `./mvnw -B -pl harness -am verify` → FAIL (`-am` builds client-java in the reactor; no separate install needed). **Step 3: Implement.** **Step 4: Run** → PASS; `docker build -f harness/Dockerfile .` succeeds.
- [x] **Step 5: Commit** `feat(harness): skeleton, configuration and container`

### Task 5: Fallback receiver

**Files:**
- Create: `harness/src/main/java/io/reliablepush/harness/receiver/{FallbackReceiverController,ReceiverMode,ReceivedWebhook,WebhookInbox,SignatureVerifier}.java`
- Test: `harness/src/test/java/io/reliablepush/harness/receiver/SignatureVerifierTest.java`, `FallbackReceiverIT.java`

**Interfaces:**
- Produces:
  - `POST /hooks/{appId}` — reads raw body bytes; verifies `X-RP-Signature` = `v1=hex(HMAC-SHA256(hmacSecret, X-RP-Timestamp + "." + body))` in constant time and timestamp skew ≤ 300 s; records a `ReceivedWebhook(String appId, UUID trackingId, String idempotencyKey, int attempt, String reason, boolean signatureValid, Instant receivedAt, int respondedStatus, Map<String,Object> body)`; then responds per mode.
  - `enum ReceiverMode { OK, FAIL_500, FAIL_400, HANG }` + `Duration hang` (default 0); `WebhookInbox` — `void setMode(String appId, ReceiverMode mode, Duration hang)`, `List<ReceivedWebhook> received(String appId)`, `List<ReceivedWebhook> forTracking(UUID id)`, `void clear()`, listener hook `onReceived(Consumer<ReceivedWebhook>)` (used by SSE, Task 8). Invalid signature → 401, recorded with `signatureValid=false`. Inbox bounded to the last 10,000 entries.
- [x] **Step 1: Failing tests** — `SignatureVerifierTest.knownVector()` (expected computed in the test with `javax.crypto.Mac`), `.rejectsTamperedBody()`, `.rejectsStaleTimestamp()` (301 s); `FallbackReceiverIT.okModeRecordsAnd200()`, `.fail500Mode()`, `.hangModeDelaysResponse()` (hang 1 s → response ≥ 1 s), `.invalidSignature401AndRecorded()`, `.idempotencyKeyRecorded()`.
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -B -pl harness -am verify` → PASS.
- [x] **Step 5: Commit** `feat(harness): fallback receiver with signature check and failure modes`

### Task 6: Device simulator

**Files:**
- Create: `harness/src/main/java/io/reliablepush/harness/devices/{DeviceSimulator,SimulatedDevice,DeviceBehaviour,GrantMinter,DeviceApiClient}.java`
- Test: `harness/src/test/java/io/reliablepush/harness/devices/{GrantMinterTest,DeviceBehaviourTest}.java`, `DeviceSimulatorIT.java` (fake backend device API with JDK HttpServer)

**Interfaces:**
- Consumes: `HarnessProperties` (Task 4); backend device API (`POST /v1/devices`, `POST /v1/devices/token:refresh`, `DELETE /v1/devices/me`, `POST /v1/events`).
- Produces:
  - `GrantMinter.mint(String appId, String userId)` → HS256 JWT `{iss: grantIssuer, aud: appId, sub: userId, iat: now, exp: now+120s}` (JDK `javax.crypto.Mac` + Base64URL; no JWT library).
  - `record SimulatedDevice(UUID deviceId, String appId, String userId, Platform platform /*IOS|ANDROID*/, AndroidMode androidMode /*V1|V2|null*/, Instant tokenExpiresAt, boolean online)`; the device token itself stays private inside `DeviceSimulator` and is never returned by any method or API.
  - `sealed interface DeviceBehaviour permits DeliverAfter, ReadAfter, Never, OfflineThenLate` — `DeliverAfter(Duration d)`, `ReadAfter(Duration deliver, Duration read)`, `Never()`, `OfflineThenLate(Duration late)`; parse from strings `deliver:3s`, `read:2s:5s`, `never`, `late:30s`.
  - `DeviceSimulator` — `SimulatedDevice register(String appId, String userId, Platform p, AndroidMode m)`; `List<SimulatedDevice> devices(String appId, String userId)`; `void receivePush(UUID trackingId, String appId, String userId, Map<UUID, DeviceBehaviour> perDevice /*default DeliverAfter(1s)*/)` scheduling DELIVERED/READ posts on a `ScheduledExecutorService`; `void refreshIfNearExpiry(UUID deviceId)` (refresh when remaining < 20 % of validity, spec §7.1); `void setOnline(UUID deviceId, boolean online)` (offline devices queue events and flush on going online); listener `onEvent(Consumer<DeviceEventRecord>)` with `record DeviceEventRecord(UUID trackingId, UUID deviceId, String type, Instant sentAt, String outcome /*from backend per-item result*/)`.
- [x] **Step 1: Failing tests** — `GrantMinterTest.validHs256Claims()` (decode and verify with Mac; `exp - iat == 120`), `DeviceBehaviourTest.parsesAllForms()` and `.rejectsGarbage()`; `DeviceSimulatorIT.registerUsesSignedGrant()` (fake server sees `idToken` that verifies), `.deliverAfterPostsDeliveredEvent()`, `.readAfterPostsBoth()`, `.neverPostsNothing()`, `.offlineQueuesThenFlushes()`, `.refreshesOnlyNearExpiry()` (validity 10 s fake: no refresh at 7 s left, refresh at 1 s left), `.tokenNeverExposed()` (reflection-free: assert `SimulatedDevice` has no token component and `toString` of simulator state contains no token).
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [x] **Step 5: Commit** `feat(harness): device simulator`

### Task 7: Fake sender and payload preview

**Files:**
- Create: `harness/src/main/java/io/reliablepush/harness/sender/{FakeSender,SendPushCommand,PayloadPreview}.java`
- Test: `harness/src/test/java/io/reliablepush/harness/sender/PayloadPreviewTest.java`, `FakeSenderIT.java`

**Interfaces:**
- Consumes: `SenderClient` (Task 2), `DeviceSimulator.receivePush` (Task 6), `HarnessProperties`.
- Produces:
  - `record SendPushCommand(String appId, String userId, String title, String body, FallbackPolicy policy, Integer timeoutMinutes, Map<String,?> fallback, Map<UUID, DeviceBehaviour> perDevice)`.
  - `FakeSender.send(SendPushCommand c)` → `Notification` (registers via `SenderClient`, then calls `receivePush`); one `SenderClient` per app, cached.
  - `PayloadPreview.apns(UUID tid, String title, String body)` → JSON string `{"aps":{"alert":{"title":…,"body":…},"mutable-content":1},"rp_tid":"<tid>"}`; `.fcmV1(...)` → `{"message":{"token":"<device-token>","android":{"priority":"HIGH"},"data":{"rp_tid":…,"title":…,"body":…}}}` (data-only); `.fcmV2(...)` → `{"message":{"token":"<device-token>","android_v2":{"remote_notification":{"notification":{"title":…,"body":…},"mutable_content":true,"data":{"rp_tid":…}}}}}` — exact shapes per spec §6; `<device-token>` is a literal placeholder.
- [x] **Step 1: Failing tests** — `PayloadPreviewTest.apnsHasMutableContentAndTid()`, `.fcmV1IsDataOnlyHighPriority()` (no `notification` key), `.fcmV2HasMutableContent()`; `FakeSenderIT.sendRegistersThenSimulatesPush()` (fake backend + real DeviceSimulator stub: sender API called once, `receivePush` called with the returned trackingId).
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [x] **Step 5: Commit** `feat(harness): fake sender and payload preview`

### Task 8: Tracking timeline and SSE stream

**Files:**
- Create: `harness/src/main/java/io/reliablepush/harness/tracking/{TrackingPoller,Timeline,TimelineEntry,ConsoleStream}.java`
- Test: `harness/src/test/java/io/reliablepush/harness/tracking/TimelineTest.java`, `TrackingPollerIT.java`

**Interfaces:**
- Consumes: `SenderClient.list/get` (Task 2), `WebhookInbox.onReceived` (Task 5), `DeviceSimulator.onEvent` (Task 6).
- Produces:
  - `record TimelineEntry(Instant at, String kind /*registered|push_simulated|event_sent|state_changed|webhook_received|cancelled*/, Map<String,Object> details)`; `Timeline` — per trackingId ordered entries + current `Notification`; `List<Notification> recent(String appId, int limit)`.
  - `TrackingPoller` — `@Scheduled(fixedDelay = 1000)`: for each app, `list(null, null, since = now − 1h, 100, …)` paging; records `state_changed` entries on transitions; **one poller regardless of browser tabs**; rebuilds the timeline from the backend on startup (Review Focus 3).
  - `ConsoleStream` — `GET /console/api/stream` (`text/event-stream`) emitting `{"type":"timeline","trackingId":…,"entry":…}` and `{"type":"webhook",…}`; `SseEmitter` per tab, timeout 0, dead emitters removed.
- [x] **Step 1: Failing tests** — `TimelineTest.ordersEntriesAndDetectsTransitions()`; `TrackingPollerIT.recordsStateChangesFromBackend()` (fake backend list endpoint returns PENDING then DELIVERED), `.rebuildsAfterRestart()` (new poller instance with empty memory shows backend notifications), `.oneBackendCallPerSecondWithThreeStreamClients()` (3 SSE clients connected → fake backend sees ≤ 2 list calls in 1.5 s per app).
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [x] **Step 5: Commit** `feat(harness): tracking timeline and sse stream`

### Task 9: Backend log tail

**Files:**
- Create: `harness/src/main/java/io/reliablepush/harness/logs/{BackendLogTail,LogQuery}.java`
- Test: `harness/src/test/java/io/reliablepush/harness/logs/BackendLogTailTest.java`

**Interfaces:**
- Consumes: `HarnessProperties.backendLogDir()` (shared volume; Task 3 writes one file per backend container, named `backend-*.json`).
- Produces: `BackendLogTail` — polls every `backend-*.json` file in the directory every 500 ms from each file's last offset (new files picked up), parses JSON lines (malformed lines skipped and counted), keeps the last 20,000 entries as `Map<String,Object>`; `List<Map<String,Object>> query(LogQuery q)` with `record LogQuery(String traceId, String trackingId, String eventAction, int limit /*≤ 500*/)`; `List<String> findSecrets(Collection<String> secrets)` → secrets (masked to first 4 chars) found in any raw line; handles file rotation/truncation (offset > size → restart at 0) and a missing file (empty result, no exception).
- [x] **Step 1: Failing tests** — `readsAppendedLines()`, `mergesSeveralFilesInTheDirectory()`, `skipsMalformedLines()`, `filtersByTrackingIdAndAction()`, `survivesTruncation()`, `missingFileIsEmpty()`, `findsSecretsMasked()`.
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [x] **Step 5: Commit** `feat(harness): backend log tail`

### Task 10: Scenario runner, scenarios and reports

**Files:**
- Create: `harness/src/main/java/io/reliablepush/harness/scenarios/{Scenario,ScenarioContext,ScenarioResult,ScenarioRunner,ScenarioReport,ScenarioController}.java`, `harness/src/main/java/io/reliablepush/harness/scenarios/catalog/*.java` (one class per scenario)
- Test: `harness/src/test/java/io/reliablepush/harness/scenarios/{ScenarioReportTest,ScenarioRunnerTest}.java`

**Interfaces:**
- Consumes: Tasks 5–9.
- Produces:
  - `interface Scenario { String name(); String description(); boolean externalControl(); void run(ScenarioContext ctx) throws Exception; }` — `externalControl()` true for scenarios needing `docker compose kill` (Task 11 drives them); `ScenarioContext` gives `FakeSender`, `DeviceSimulator`, `WebhookInbox`, `Timeline`, `BackendLogTail`, `awaitState(UUID, NotificationState, Duration)` (polls `get` every 200 ms), `fail(String expected, String actual, String hint)` (throws `ScenarioFailure`), a per-run unique `userId` prefix, and `secrets()` (all secrets of the run for the hygiene scan).
  - `record ScenarioResult(String scenario, String status /*PASSED|FAILED|SKIPPED*/, long durationMs, Failure failure /*nullable*/, List<String> logs)`; `record Failure(String expected, String actual, String hint)`.
  - `ScenarioRunner.runAll()` / `run(String name)` — sequential, one run at a time (`AtomicBoolean`; concurrent call → `IllegalStateException`), per-scenario timeout 90 s, receiver reset between scenarios.
  - `ScenarioReport.summaryJson(List<ScenarioResult>)` → JSON array exactly as Global Constraints; `.junitXml(List<ScenarioResult>)` → one `<testsuite name="reliable-push-e2e">` with a `<testcase>` per scenario and `<failure message="…">expected / actual / hint</failure>`.
  - REST (`ScenarioController`): `GET /console/api/scenarios` (name, description, externalControl); `POST /console/api/scenarios/run` body `{"names": [...]}` or `{}` = all non-external → 200 `{"results": [...]}`; concurrent → **409** problem (Review Focus 4); `POST /console/api/scenarios/{name}/prepare` and `/{name}/verify` for external-control scenarios (Task 11); `GET /console/api/scenarios/report/summary.json` and `/report/junit.xml` for the last run.
  - Scenario catalogue (names exact; fast clock 1 min = 1 s; app `shop` timeout 5, window 20, backoff 1,2,4,8 unless stated): `delivered-before-timeout` (deliver:1s → DELIVERED, no webhook after 8 s); `read-policy-read` (NOT_READ, read:1s:2s → READ, no webhook); `read-policy-delivered-only` (NOT_READ, deliver:1s → FALLBACK_TRIGGERED, exactly 1 webhook); `not-delivered-single-fallback` (never → exactly 1 webhook, valid signature, Idempotency-Key = trackingId); `late-delivery-after-fallback` (late:10s → FALLBACK_TRIGGERED stays, device event outcome LATE); `multi-device` (2 devices: never + deliver:2s → DELIVERED, no webhook); `cancellation` (cancel at 1 s → CANCELLED, no webhook); `receiver-retries-then-succeeds` (receiver FAIL_500 for the first 2 calls of that trackingId, `WebhookInbox.failNext(trackingId, 2)` → 3 webhooks attempts 1..3, FALLBACK_TRIGGERED); `receiver-final-failure` (FAIL_400 → 1 webhook, FALLBACK_FAILED); `retries-exhausted-fails` (FAIL_500 always → 5 attempts (max-attempts) inside the 20 s window → FALLBACK_FAILED; replaces `retries-expire-in-window`, expiry is covered by `worker-crash-outside-window`); `token-refresh-near-expiry` (app `news` with device-token-validity `PT20S`: refresh happens and later events still accepted); `rate-limited` (app `limited`, device-per-minute 2 → third events call gets 429 with Retry-After); `log-catalogue` (for one fallback run: backend log has `notification.registered`, `tracking.event_accepted`, `fallback.claimed`, `fallback.dispatched` for the trackingId, all with the same `rp.tracking_id`; `fallback.claimed` and `fallback.dispatched` share one worker `trace.id` distinct from the API request's); `log-hygiene` (Review Focus 5; candidates: app sender keys, grant secrets, HMAC secrets, issued device tokens, minted signed grants, received `X-RP-Signature` values, scenario fallback texts and user ids — harness lifetime, bounded); external: `worker-crash-inside-window`, `worker-crash-outside-window` (Task 11).
- [x] **Step 1: Failing tests** — `ScenarioReportTest.summaryMatchesSchema()`, `.junitXmlHasFailureDetails()`, `.escapesXml()`; `ScenarioRunnerTest.secondConcurrentRunRejected()`, `.timeoutMarksFailed()` (scenario sleeping beyond a test timeout of 200 ms), `.failureCarriesExpectedActualHint()`.
- [x] **Step 2: Run** → FAIL. **Step 3: Implement** runner, report, controller and catalogue (catalogue scenarios are exercised for real in Task 11). **Step 4: Run** `./mvnw -B -pl harness -am verify` → PASS.
- [x] **Step 5: Commit** `feat(harness): scenario runner, catalogue and reports`

### Task 11: Compose stack, secrets and E2E scripts

**Files:**
- Create: `harness/compose/docker-compose.yml`, `harness/compose/GenSecrets.java` (single-file Java program), `harness/compose/backend.env.template`, `scripts/e2e.sh`, `scripts/e2e.ps1`, `scripts/lib/common.sh`
- Modify: `.gitignore` (`harness/build/`), `.agents/skills/run-e2e/SKILL.md` (real commands, remove "not built yet" note)

**Interfaces:**
- Consumes: backend image (`backend/Dockerfile`), harness image (Task 4), Task 3 log file, Task 10 REST.
- Produces:
  - Compose services: `postgres` (`postgres:16-alpine`, healthcheck), `api` (backend image, profiles `api,json,e2e`, port `127.0.0.1:8080:8080`), `worker-a` and `worker-b` (backend image, profiles `worker,json,e2e`, no ports), `harness` (port `127.0.0.1:8090:8090`), `secrets` (one-shot, `eclipse-temurin:21.0.13_9-jdk-alpine-3.24`, runs `java /work/GenSecrets.java /out`), shared volume `rp-logs` mounted at `/tmp/rp-logs` (explicit per service: `RP_LOG_FILE=/tmp/rp-logs/backend-api.json`, `…/backend-worker-a.json`, `…/backend-worker-b.json`; harness `HARNESS_BACKENDLOGDIR=/tmp/rp-logs`), `env_file: ../build/e2e/secrets.env`. All backend containers: `read_only: true`, `tmpfs: /tmp` except the logs volume, `user: "1000870000:0"`.
  - Backend app config (in `backend.env.template`, merged into `secrets.env`): for each app `RELIABLEPUSH_APPS_<ID>_IDENTITY_TYPE=signed-grant`, `…_IDENTITY_ISSUER=reliable-push-harness`, `…_IDENTITY_AUDIENCE=<id>`, `…_IDENTITY_GRANTSECRETREF=env:RP_<ID>_GRANT_SECRET`, `…_FALLBACK_URL=http://harness:8090/hooks/<id>`, `…_FALLBACK_HMACSECRETREF=env:RP_<ID>_HOOK_SECRET`, `…_DEFAULTTIMEOUTMINUTES=5`; app `news` `…_DEVICETOKENVALIDITY=PT20S`; app `limited` `…_RATELIMITS_DEVICEPERMINUTE=2`; datasource `jdbc:postgresql://postgres:5432/reliablepush`; `RELIABLEPUSH_TESTING_MINUTEDURATION=1s`.
  - `GenSecrets.java` writes `secrets.env` with: per app (`shop`, `news`, `limited`) a random 32-byte sender API key + its SHA-256 hex, 32-byte grant secret, 32-byte HMAC secret; one P-256 signing key pair as PKCS#8/SPKI PEM (JDK `KeyPairGenerator`), with both the `RELIABLEPUSH_…` backend variables and the `HARNESS_APPS_…` harness variables. Re-running overwrites.
  - `e2e.sh [--scenario <name>] [--keep]`: checks ports 8080/8090 free (Review Focus 2) and `docker compose version`; builds images; runs `secrets`; `up -d`; waits ≤ 120 s for readiness of `api`, `worker-a`, `worker-b`, `harness` (Review Focus 1: on timeout print the container name and dump logs); `POST /console/api/scenarios/run`; then for each external scenario: `docker compose stop worker-b` (no second worker competes) → `prepare` (the receiver hangs only the next `shop` call for 8 s via `WebhookInbox.hangNext(shop, 1, 8s)` — shorter than the 10 s webhook http-timeout and the 25 s restart delay; mode stays OK; the runner stays reserved for that scenario, other runs get 409, until verify or 120 s) → poll `GET /console/api/inbox/in-flight?appId=shop` until it lists the trackingId → `docker compose kill worker-a` while that call hangs → wait 3 s (`inside-window`) or 25 s (`outside-window`) → start both workers → `verify` (inside: re-sent call answered 200 at once, FALLBACK_TRIGGERED, ≥ 2 calls with one Idempotency-Key, `fallback.reclaimed`; outside: FALLBACK_EXPIRED, exactly the 1 hung call); writes `summary.json` and `junit/e2e.xml` from the report endpoints; copies `docker compose logs` per service to `logs/`; `down -v` unless `--keep`; exit `0` only if every scenario PASSED. Self-test hook: `E2E_READY_TIMEOUT` env (default 120) so the timeout path is testable with a tiny value.
  - `e2e.ps1`: same behaviour in PowerShell 7.
- [x] **Step 1: Failing check** — run `./scripts/e2e.sh` → fails (no compose file).
- [x] **Step 2: Implement** compose, secrets, scripts.
- [x] **Step 3: Verify Review Focus 1 and 2** — `nc -l 127.0.0.1 8090 & ./scripts/e2e.sh; echo $?` → non-zero with "port 8090 is in use"; `E2E_READY_TIMEOUT=1 ./scripts/e2e.sh` → non-zero within ~10 s naming a container, logs written.
- [x] **Step 4: Run the full suite** `./scripts/e2e.sh` → exit 0, `harness/build/e2e/summary.json` lists all catalogue scenarios incl. both external ones as PASSED. Fix (test-first at the lowest level) until green; per the run-e2e skill, never weaken a scenario.
- [x] **Step 5: Commit** `feat(harness): compose stack, generated secrets and e2e scripts` (body: `E2E: n/n passed (./scripts/e2e.sh, <date>)`).

### Task 12: Web console UI

**Files:**
- Create: `harness/src/main/resources/static/console/{index.html,console.css,app.js,api.js,views/devices.js,views/send.js,views/tracking.js,views/inbox.js,views/scenarios.js,views/logs.js}`, `harness/src/main/java/io/reliablepush/harness/console/ConsoleApiController.java`
- Test: `harness/src/test/java/io/reliablepush/harness/console/ConsoleApiIT.java`

**Interfaces:**
- Consumes: Tasks 5–10.
- Produces REST under `/console/api`: `GET apps`; `GET/POST devices` (`{appId,userId,platform,androidMode}`), `POST devices/{id}/refresh`, `POST devices/{id}/online` (`{online}`); `POST push` (`SendPushCommand` JSON, behaviours as strings like `deliver:3s`) → `{trackingId}`; `GET notifications?appId=` (from `Timeline.recent`); `GET notifications/{id}` (notification + timeline); `POST notifications/{id}/cancel`; `GET payload-preview?trackingId=&title=&body=` → `{apns, fcmV1, fcmV2}`; `GET inbox?appId=`; `POST inbox/mode` (`{appId, mode, hangMs}`); `GET logs?traceId=&trackingId=&eventAction=&limit=`; plus Task 8 stream and Task 10 scenario endpoints. No endpoint returns device tokens or harness secrets.
- UI: single page, tab navigation for the six screens of spec §10.2 (Devices, Send push, Live tracking, Fallback inbox, Scenarios, Logs); permanent banner text exactly "Test harness — simulated data, not for production"; live tracking table with state badges, countdowns to `dueAt` and to `dueAt + window` (window from `GET apps`), detail timeline; copy buttons for payload previews; works in current Chrome, Firefox, Safari, Edge; no external requests (no CDN, no fonts).
- [x] **Step 1: Failing tests** — `ConsoleApiIT.createDeviceAndList()`, `.pushReturnsTrackingId()`, `.notificationDetailHasTimeline()`, `.payloadPreviewHasThreeShapes()`, `.inboxModeSwitch()`, `.noTokenOrSecretInAnyResponse()` (crawl all GET endpoints after a push; assert none of the run's secrets/device tokens appear), `.staticConsoleServedWithBanner()`.
- [x] **Step 2: Run** → FAIL. **Step 3: Implement** controller and static UI. **Step 4: Run** `./mvnw -B -pl harness -am verify` → PASS.
- [x] **Step 5: Commit** `feat(harness): web console`

### Task 13: Demo scripts and console smoke test

**Files:**
- Create: `scripts/demo.sh`, `scripts/demo.ps1`, `harness/src/test/java/io/reliablepush/harness/console/ConsoleSmokeE2E.java` (JUnit 5, `@Tag("console")`, excluded from default `verify`; enabled by Maven profile `console-smoke`)
- Modify: `harness/pom.xml` (profile `console-smoke`), `scripts/e2e.sh`/`e2e.ps1` (after catalogue scenarios run `./mvnw -B -pl harness -am verify -Pconsole-smoke -Dconsole.url=http://127.0.0.1:8090` and add its JUnit XML + a `console-smoke` entry to `summary.json`)

**Interfaces:**
- Produces: `demo.sh [--realtime]` — same stack as E2E but profile `demo` instead of `e2e`, keeps running, opens `http://127.0.0.1:8090` (`open`/`xdg-open`; print URL if neither), prints how to stop (`docker compose -p reliable-push-demo down -v`); `--realtime` removes `RELIABLEPUSH_TESTING_MINUTEDURATION`.
- [ ] **Step 1: Failing test** `ConsoleSmokeE2E.registerDeviceSendPushSeeDeliveredAndFallback()` — headless Chromium via Playwright: banner text visible; create device (Android v1) for a fresh user; send push with behaviour `deliver:1s` → tracking row shows `DELIVERED` within 10 s; send push with `never` → inbox shows one webhook with "signature valid" within 20 s.
- [ ] **Step 2: Run** against a running demo (`./scripts/demo.sh` in another shell, then `./mvnw -B -pl harness -am verify -Pconsole-smoke`) → FAIL before UI fixes / PASS after. **Step 3: Implement** scripts and profile. **Step 4: Run** `./scripts/e2e.sh` → exit 0 including `console-smoke`.
- [ ] **Step 5: Commit** `feat(harness): demo scripts and console smoke test`

### Task 14: CI for client and harness, docs and handoff

**Files:**
- Create: `.github/workflows/harness-ci.yml`
- Modify: `.github/dependabot.yml` (maven `/client-java`, `/harness`; docker `/harness`), `docs/testing.md` (E2E + console sections replacing "arrives with plan 1B"), `docs/integration-guide.md` (client-java usage), `README.md` (components table, demo pointer), `AGENTS.md` (Commands: client, harness, e2e, demo), `docs/STATUS.md`, `CONTRIBUTING.md` (E2E line now real)
- [ ] **Step 1: Write workflow** — triggers like backend-ci with paths `client-java/**`, `harness/**`, workflow file; jobs: `client` (`./mvnw -B -pl client-java verify`, assert no runtime deps via enforcer), `harness` (`./mvnw -B -pl harness -am verify`, excluding `console` tag); actions pinned to the same SHAs as backend-ci; **no** e2e/compose job (spec §10.1).
- [ ] **Step 2: Validate** `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12` → no errors.
- [ ] **Step 3: Docs** — every command in docs runs as written (execute each once); all links resolve.
- [ ] **Step 4: Final verification** — `./mvnw -B -pl client-java verify` and `./mvnw -B -pl harness -am verify` green; `./scripts/e2e.sh` exit 0; record counts and the E2E line in `docs/STATUS.md`.
- [ ] **Step 5: Commit** `ci: client and harness builds; docs: e2e and console`; then run the `session-handoff` skill.
