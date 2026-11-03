# Cycle 1A — Repo Scaffolding + Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Non-Claude tools: use `.agents/skills/implement-task`. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A production-ready reliable-push backend (sender API, device API, tracking, fallback worker, logging, metrics) with MIT repo scaffolding, container image and CI.

**Architecture:** One Spring Boot 4.x modular monolith (`io.reliablepush.*`), profiles `api` / `worker` (both by default), PostgreSQL via `JdbcClient` + Flyway, auth as small servlet filters on `nimbus-jose-jwt`, Log4j 2 ECS JSON logging.

**Tech Stack:** Java 21, Spring Boot 4.x (latest GA at execution, pinned), Maven wrapper, PostgreSQL 16, Flyway, nimbus-jose-jwt, Bucket4j core, Log4j 2 + `log4j-layout-template-json`, Micrometer Prometheus; tests: JUnit 5, AssertJ, Testcontainers, WireMock, Log4j `log4j-core-test`.

**Spec:** `docs/specs/2026-11-03-reliable-push-design.md` (read §1–§9, §10, §11). Requirements: `docs/requirements.md`.

**Follow-up plans (written after this one lands):** 1B `client-java` + harness + `scripts/e2e.sh` + web console; 1C Helm chart (GKE/OpenShift) + Gatling load test.

## Global Constraints

- Java 21 (`maven.compiler.release=21`), virtual threads on (`spring.threads.virtual.enabled=true`).
- Dependency budget (spec §9.2): allowed — Spring Boot web (Tomcat), `spring-boot-starter-jdbc` (`JdbcClient`), Actuator, Micrometer Prometheus, Flyway, PostgreSQL driver, `spring-boot-starter-log4j2` (Logback excluded) + `log4j-layout-template-json`, Bean Validation, `nimbus-jose-jwt`, Bucket4j core. **Not allowed:** JPA/Hibernate, Spring Security, Spring Cloud, Lombok, MapStruct, reactive stack, brokers.
- Packages: `io.reliablepush.{apps,notifications,devices,tracking,fallback,shared}`; modules use each other only via public services.
- Errors: RFC 9457 `application/problem+json`.
- Limits: request body ≤ 64 KB; `fallback` payload ≤ 4 KB; events batch ≤ 50; list `limit` ≤ 100.
- Defaults: device-token validity 90 d; client refresh threshold 20 %; rate limits device 60 req/min, sender key 6,000 req/min; worker poll 5 s, batch 100, lease 2 min; webhook max 5 attempts, backoff 1, 2, 4, 8 min, HTTP timeout 10 s; trigger window 20 min (must be ≥ cumulative backoff); retention 30 d.
- Logging: catalogue events of spec §9.1 with ECS fields; never log keys, tokens, secrets, signatures, raw user ids (`rp.user_hash` = first 16 hex chars of SHA-256(userId)), message content, bodies.
- Gitflow: one `feature/<task-slug>` branch per task from `develop`, merged `--no-ff`; Conventional Commits; tick the task's checkboxes in this file in the same commit.
- Test naming: unit `*Test`, integration `*IT` (Testcontainers PostgreSQL; never mock the DB). `./mvnw verify` runs both.

## Review Focus

1. **Client clock skew** — device `occurredAt` in the future or days old: state decisions use server `received_at`; `occurredAt` is stored only, clamped to `[received_at - 7d, received_at]`. Test in Task 11.
2. **Events for closed notifications** (CANCELLED, FALLBACK_*): accepted as `ignored` with 200, recorded `late=true`, state unchanged — never 409. Test in Task 11.
3. **Duplicate trackingIds inside one batch** and unknown trackingIds mixed with valid ones: per-item outcome, one bad item never fails the batch. Test in Task 11.
4. **Webhook redirects and slow receivers** — 3xx is a failure (redirects not followed), a receiver hanging past 10 s is a failed attempt, not a stuck worker. Test in Task 13.
5. **Signing-key rotation** — tokens signed with a previous key id remain valid until expiry while new tokens use the current key. Test in Task 8.

---

### Task 0: Local prerequisites

**Files:** none (environment only; record result in `docs/STATUS.md`).

- [ ] **Step 1: Install JDK 21, Maven (only to generate the wrapper) and a Docker runtime** — needs maintainer approval on the machine:
```bash
brew install openjdk@21 maven colima docker docker-compose
colima start --cpu 4 --memory 6
```
(Docker Desktop or Podman are equally fine.)
- [ ] **Step 2: Verify**
Run: `/opt/homebrew/opt/openjdk@21/bin/java -version && docker info --format '{{.ServerVersion}}'`
Expected: `openjdk version "21...` and a server version. Export `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for all later commands.

### Task 1: Repository scaffolding

**Files:**
- Create: `LICENSE` (MIT, `Copyright (c) 2026 reliable-push contributors`), `README.md` (replace), `CONTRIBUTING.md`, `SECURITY.md`, `.gitignore` (Java/Maven/IDE/macOS, `.env`), `.editorconfig`, `.gitattributes` (`* text=auto`, `*.sh eol=lf`)

- [x] **Step 1: Write the files.** README: one-paragraph pitch, how it works (the 5-step flow of spec §1 as a list), component table, quick start placeholder pointing to `docs/integration-guide.md`, and a **Documentation** section linking `docs/requirements.md`, `docs/specs/2026-11-03-reliable-push-design.md`, `docs/architecture.md`, `docs/api.md`, `docs/integration-guide.md`, `docs/security.md`, `docs/operations.md`, `docs/testing.md`, `docs/STATUS.md`, `AGENTS.md`, `CONTRIBUTING.md`, `SECURITY.md`, `LICENSE`. CONTRIBUTING: gitflow, Conventional Commits, test-first, review-loop, E2E-local rule, AI agents → `AGENTS.md`. SECURITY: private reporting via GitHub Security Advisories.
- [x] **Step 2: Verify links** — Run: `grep -o '](\(docs/\|[A-Z]\)[^)]*)' README.md | sort -u` — every target either exists or is created by a later task of this plan (list them in the commit message).
- [x] **Step 3: Commit** `docs: add MIT license, README and contribution files`

### Task 2: Backend skeleton, profiles and Log4j 2 ECS logging

**Files:**
- Create: `backend/pom.xml`, `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/*`, `backend/src/main/java/io/reliablepush/ReliablePushApplication.java`, `backend/src/main/resources/application.yml`, `backend/src/main/resources/log4j2-spring.xml`, `backend/src/test/java/io/reliablepush/support/PostgresTestBase.java`, `backend/src/test/java/io/reliablepush/ApplicationStartsIT.java`

**Interfaces:**
- Produces: `PostgresTestBase` — abstract `@SpringBootTest(webEnvironment = RANDOM_PORT)` base with a static `PostgreSQLContainer("postgres:16-alpine")` and `@ServiceConnection`; field `protected TestRestTemplate http` (or `RestClient` bound to the random port). Every `*IT` extends it.

- [x] **Step 1: Generate wrapper** — `cd backend && mvn -N wrapper:wrapper` after writing `pom.xml` (parent `spring-boot-starter-parent` 4.x pinned; deps per Global Constraints; `spring-boot-starter-web` with `spring-boot-starter-logging` excluded; test deps: `spring-boot-starter-test`, `spring-boot-testcontainers`, `testcontainers:postgresql`, `wiremock-standalone`, `log4j-core-test`; plugins: surefire, failsafe, jacoco with line-coverage check `0.80` on `verify`).
- [x] **Step 2: Write failing test** `ApplicationStartsIT`:
  - `healthIsUp()` — GET `/actuator/health` → 200, body `status` = `UP`.
  - `livenessAndReadinessExposed()` — GET `/actuator/health/liveness` and `/readiness` → 200.
  - `prometheusExposed()` — GET `/actuator/prometheus` → 200, contains `jvm_threads`.
- [x] **Step 3: Run** `./mvnw verify -Dit.test=ApplicationStartsIT` → FAIL (no application class).
- [x] **Step 4: Implement** `ReliablePushApplication` and `application.yml`: `spring.threads.virtual.enabled: true`, `spring.profiles.default: api,worker` plus active logging profile `json` by default, actuator exposure `health,prometheus`, probes enabled, `server.tomcat.max-swallow-size`/`spring.servlet` limits for 64 KB bodies, `spring.flyway.enabled: true`. `log4j2-spring.xml`: `<SpringProfile name="json">` console with `JsonTemplateLayout eventTemplateUri="classpath:EcsLayout.json"` and `EventTemplateAdditionalField`s `service.name=reliable-push`, `service.version=${project.version}` (resource-filtered); `<SpringProfile name="local">` `PatternLayout` `%d{ISO8601} %-5level [%t] %c{1.} [trace=%X{trace.id}] - %m%n%throwable`. Root level INFO.
- [x] **Step 5: Run** `./mvnw verify` → PASS.
- [x] **Step 6: Commit** `feat(backend): spring boot skeleton with log4j2 ecs logging`

### Task 3: Shared logging primitives — trace id, access log, event log

**Files:**
- Create: `backend/src/main/java/io/reliablepush/shared/logging/EventLog.java`, `UserHash.java`, `TraceIdFilter.java`, `AccessLogFilter.java`
- Test: `backend/src/test/java/io/reliablepush/shared/logging/EventLogTest.java`, `UserHashTest.java`, `TraceIdFilterTest.java`, `backend/src/test/java/io/reliablepush/shared/logging/AccessLogIT.java`

**Interfaces:**
- Produces:
  - `final class EventLog` — `static void info(String action, Map<String, ?> fields)`, `static void warn(String action, Map<String, ?> fields)`; emits a Log4j `MapMessage` whose keys become ECS fields: `event.action=action`, plus given fields (`event.outcome`, `rp.app_id`, `rp.tracking_id`, `rp.device_id`, `rp.user_hash`, …). Rejects (throws `IllegalArgumentException`) keys in the deny-list `{"token","idToken","apiKey","secret","signature","message","body","userId"}`.
  - `final class UserHash` — `static String of(String userId)` → first 16 lowercase hex chars of SHA-256(UTF-8).
  - `TraceIdFilter` (order highest) — reads W3C `traceparent` (trace-id part) or `X-Request-Id`; else generates 32-hex id; sets `ThreadContext` key `trace.id`; sets response header `X-Request-Id`; clears context after the request.
  - `AccessLogFilter` — after each request logs `api.request` with `http.request.method`, `url.path` (Spring best-matching **pattern**, e.g. `/v1/notifications/{trackingId}`), `http.response.status_code`, `event.duration` (ns), `event.outcome` (`success` < 400, else `failure`), `rp.app_id` if a request attribute `rp.app_id` was set by auth.

- [x] **Step 1: Failing tests**
  - `UserHashTest.hashesDeterministically()` — `UserHash.of("alice")` equals `"2bd806c97f0e00af"` (first 16 of SHA-256("alice")), length 16.
  - `EventLogTest.emitsActionAndFields()` — with a `ListAppender`, `EventLog.info("notification.registered", Map.of("rp.tracking_id","t1"))` → one event, map has `event.action`=`notification.registered`, `rp.tracking_id`=`t1`.
  - `EventLogTest.rejectsSensitiveKeys()` — `EventLog.info("x", Map.of("token","abc"))` throws `IllegalArgumentException`.
  - `TraceIdFilterTest.usesTraceparent()` — header `traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01` → `X-Request-Id` = `4bf92f3577b34da6a3ce929d0e0e4736`; `generatesWhenMissing()` → 32 hex chars.
  - `AccessLogIT.logsEveryRequestWithPathTemplate()` — GET `/actuator/health` → captured `api.request` event with `url.path`=`/actuator/health`, status `200`, numeric `event.duration`; and the JSON output line parses as JSON with `@timestamp`, `log.level`, `trace.id`.
- [x] **Step 2: Run** `./mvnw test -Dtest='UserHashTest,EventLogTest,TraceIdFilterTest'` → FAIL.
- [x] **Step 3: Implement** the four classes; register filters as `FilterRegistrationBean`s in `shared/logging/LoggingConfig.java`.
- [x] **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): trace id, access log and event log`

### Task 4: App registry, configuration validation and time scale

**Files:**
- Create: `backend/src/main/java/io/reliablepush/apps/ReliablePushProperties.java`, `AppConfig.java`, `AppRegistry.java`, `backend/src/main/java/io/reliablepush/shared/time/TimeScale.java`, `backend/src/main/java/io/reliablepush/shared/time/ClockConfig.java`
- Test: `backend/src/test/java/io/reliablepush/apps/AppRegistryTest.java`, `ReliablePushPropertiesTest.java`, `backend/src/test/java/io/reliablepush/shared/time/TimeScaleTest.java`, `backend/src/test/resources/application-test.yml`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("reliable-push") @Validated record ReliablePushProperties(DeviceToken deviceToken, Worker worker, int retentionDays, Testing testing, Map<String, AppProperties> apps)` with nested records matching spec §8 keys (`device-token.signing-keys` list of `{kid, private-key-pem-ref}` + `current-kid` + `revocation-cache-ttl` default `60s`; `worker.poll-interval`, `batch-size`, `lease`; `testing.minute-duration`; per app: `sender-api-key-sha256`, `identity{type: oidc|signed-grant, issuer, audience, user-claim, jwks-uri?, grant-secret-ref?}`, `device-token-validity`, `default-policy`, `default-timeout-minutes`, `rate-limits{device-per-minute, sender-per-minute}`, `fallback{url, hmac-secret-ref, max-attempts, backoff (list of Duration), trigger-window-minutes, http-timeout}`). `*-ref` values resolve `env:NAME` from the environment.
  - `record AppConfig(String appId, …resolved values…)`.
  - `AppRegistry` — `Optional<AppConfig> byId(String appId)`, `Optional<AppConfig> bySenderApiKey(String rawKey)` (SHA-256 of raw key compared with `MessageDigest.isEqual` against every app), `Collection<AppConfig> all()`.
  - `TimeScale` — `Duration minutes(int n)` returns `n × minuteDuration` (default `1m`).
  - `ClockConfig` — `@Bean Clock clock()` = `Clock.systemUTC()`; tests override with a mutable test clock.

- [x] **Step 1: Failing tests**
  - `AppRegistryTest.findsAppBySenderKey()` — app `shop` with hash of `"k-123"` → `bySenderApiKey("k-123")` present with appId `shop`; `bySenderApiKey("wrong")` empty.
  - `ReliablePushPropertiesTest.rejectsWindowShorterThanBackoff()` — backoff `[1m,2m,4m,8m]`, window `14` → context fails to start with message containing `trigger-window-minutes`.
  - `ReliablePushPropertiesTest.defaultsApplied()` — minimal app config → validity `90d`, timeout `10`, max attempts `5`, window `20`, rate limits `60`/`6000`.
  - `TimeScaleTest.fastClockOnlyInE2eOrDemo()` — `testing.minute-duration=1s` with profiles `api` → startup fails (`minute-duration is only allowed in profiles e2e or demo`); with profile `e2e` → `minutes(10)` = `10s`.
- [x] **Step 2: Run** `./mvnw test -Dtest='AppRegistryTest,ReliablePushPropertiesTest,TimeScaleTest'` → FAIL.
- [x] **Step 3: Implement**; the window check and the profile check run in a `@PostConstruct`/`InitializingBean` validator that throws `IllegalStateException`.
- [x] **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): app registry and configuration validation`

### Task 5: Database schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init.sql`
- Test: `backend/src/test/java/io/reliablepush/SchemaIT.java`

- [x] **Step 1: Failing test** `SchemaIT`:
  - `tablesExist()` — `information_schema.tables` contains `notification`, `notification_event`, `device`.
  - `dueIndexIsPartial()` — `pg_indexes.indexdef` for `notification_due` contains `WHERE (due_at IS NOT NULL)`.
  - `stateConstraint()` — inserting `state='BOGUS'` throws `DataIntegrityViolationException`.
  - `externalIdUniquePerApp()` — two rows same `(app_id, external_id)` → violation; same external_id other app → ok.
- [x] **Step 2: Run** → FAIL.
- [x] **Step 3: Write `V1__init.sql`** exactly per spec §3, plus: `CHECK (state IN ('PENDING','DELIVERED','READ','DISPATCHING','FALLBACK_TRIGGERED','FALLBACK_FAILED','FALLBACK_EXPIRED','CANCELLED'))`, `CHECK (policy IN ('NOT_DELIVERED','NOT_READ'))`, `notification_event.type CHECK (type IN ('DELIVERED','READ'))`, `notification_event(notification_id, type)` index, `device(app_id, user_id)` index, `notification(app_id, user_id, created_at desc)` index for listing, `notification(closed_at) where closed_at is not null` index for retention.
- [x] **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): initial database schema`

### Task 6: Problem details, sender API-key filter and rate limiting

**Files:**
- Create: `backend/src/main/java/io/reliablepush/shared/web/ProblemHandler.java`, `backend/src/main/java/io/reliablepush/shared/security/SenderApiKeyFilter.java`, `SenderPrincipal.java`, `backend/src/main/java/io/reliablepush/shared/ratelimit/RateLimiter.java`, `RateLimitFilter.java`
- Test: `backend/src/test/java/io/reliablepush/shared/ratelimit/RateLimiterTest.java`, `backend/src/test/java/io/reliablepush/shared/security/SenderApiKeyIT.java`

**Interfaces:**
- Produces:
  - `SenderApiKeyFilter` on `/v1/notifications/**`: header `X-Api-Key` → `AppRegistry.bySenderApiKey`; on success request attributes `rp.app_id` and `SenderPrincipal(String appId)` (attribute name `SenderPrincipal.ATTR`); on failure 401 problem, `auth.failed` WARN with `reason` = `missing_key|unknown_key`.
  - `interface RateLimiter { boolean tryConsume(String bucketKey, int perMinute); long retryAfterSeconds(String bucketKey); }` — Bucket4j in-memory implementation `InMemoryRateLimiter` (Caffeine-free: `ConcurrentHashMap` with idle eviction every 10 min).
  - `RateLimitFilter` — keys `sender:<appId>` (after API-key filter) and `device:<deviceId>` (after device filter, Task 10); 429 problem with `Retry-After`, `rate_limit.exceeded` WARN.
  - `ProblemHandler` — `@RestControllerAdvice` mapping: `MethodArgumentNotValidException`/`ConstraintViolationException` → 422, payload too large → 413, `NotFoundException` → 404, `ConflictException` → 409, `ForbiddenException` → 403 (defined in `shared/web`), all as `ProblemDetail`.
- [x] **Step 1: Failing tests**
  - `RateLimiterTest.allowsUpToLimitThenRejects()` — 60/min: 60 × true, 61st false, `retryAfterSeconds` in `1..60`.
  - `SenderApiKeyIT.missingKey401()`, `.unknownKey401()` (both `application/problem+json`, `auth.failed` logged without the key value — assert raw key absent from captured logs), `.validKeyPasses()` (GET `/v1/notifications/<random uuid>` → 404, not 401), `.rateLimited429()` (app with `sender-per-minute: 2` in test config → third call 429 with `Retry-After`), `.bodyOver64KbIs413()` (POST `/v1/notifications` with a 65 KB body → 413 problem).
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): sender api key auth, rate limiting, problem details`

### Task 7: Notifications module (sender API, contract-first)

**Files:**
- Create: `backend/src/main/resources/openapi.yaml` (full contract for spec §4.1–§4.3; device/events paths included now), `backend/src/main/java/io/reliablepush/notifications/{NotificationController,NotificationService,NotificationRepository,Notification,NotificationState,FallbackPolicy,RegisterNotificationRequest,NotificationView,NotificationPage}.java`
- Test: `backend/src/test/java/io/reliablepush/notifications/NotificationServiceTest.java`, `NotificationApiIT.java`, `backend/src/test/java/io/reliablepush/support/OpenApiAssert.java`

**Interfaces:**
- Consumes: `SenderPrincipal`, `AppRegistry`, `TimeScale`, `Clock`, `EventLog`.
- Produces:
  - `enum NotificationState { PENDING, DELIVERED, READ, DISPATCHING, FALLBACK_TRIGGERED, FALLBACK_FAILED, FALLBACK_EXPIRED, CANCELLED; boolean closed(); String external() }` — `external()` maps `DISPATCHING` → `FALLBACK_PENDING`.
  - `enum FallbackPolicy { NOT_DELIVERED, NOT_READ }`.
  - `NotificationService` — `NotificationView register(String appId, RegisterNotificationRequest r)`; `Optional<NotificationView> get(String appId, UUID id)`; `NotificationPage list(String appId, String userId, NotificationState state, Instant since, int limit, String cursor)`; `void cancel(String appId, UUID id)`.
  - `NotificationRepository` (package-private API used only by `notifications`, `tracking`, `fallback` through these public methods): `insert(Notification)`, `findById(UUID)`, `findByExternalId(String appId, String externalId)`, `page(...)`, `boolean markDelivered(UUID id, Instant at)` / `boolean markRead(UUID id, Instant at)` (conditional updates per spec §2, clearing `due_at` when the policy is satisfied), `boolean cancel(UUID)`, `List<Notification> claimDue(Instant now, int batch, Duration lease)`, `void reschedule(UUID, Instant nextAttemptAt, int attempts)`, `void close(UUID, NotificationState finalState, Instant at)`, `int purgeClosedBefore(Instant)`.
  - `OpenApiAssert.assertMatchesContract(String method, String path, int status, String body)` — validates responses against `openapi.yaml` (test-only `com.atlassian.oai:swagger-request-validator-core`).
- [x] **Step 1: Failing tests**
  - `NotificationServiceTest.appliesAppDefaults()` — no policy/timeout → `NOT_DELIVERED`, `dueAt = createdAt + 10 min` (fixed clock).
  - `NotificationApiIT.registerReturns201WithTrackingIdAndDueAt()`; `.duplicateExternalIdReturnsExisting()` (same `trackingId`, `notification.duplicate` logged); `.getShowsExternalState()`; `.otherAppCannotRead()` (404); `.listNewestFirstWithCursor()` (3 rows, `limit=2` → 2 + `nextCursor`, next page 1); `.limitAbove100Is422()`; `.fallbackOver4KbIs413or422()` (assert 422 with field `fallback`); `.cancelPendingIs204ThenCancelAgain409()`; `.responsesMatchContract()` (via `OpenApiAssert`); `.registerLogsEventWithoutUserId()` — `notification.registered` has `rp.user_hash`, no raw `userId` anywhere in captured logs.
- [x] **Step 2: Run** → FAIL. **Step 3: Implement** (cursor = base64url of `created_at|id`). **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): sender notifications api`

### Task 8: Device token service (ES256, rotation, revocation)

**Files:**
- Create: `backend/src/main/java/io/reliablepush/devices/{DeviceTokenService,DeviceClaims,SigningKeys}.java`
- Test: `backend/src/test/java/io/reliablepush/devices/DeviceTokenServiceTest.java`, `backend/src/test/java/io/reliablepush/support/TestKeys.java`

**Interfaces:**
- Produces:
  - `record DeviceClaims(String appId, String userId, UUID deviceId, int tokenVersion, Instant issuedAt, Instant expiresAt)`.
  - `DeviceTokenService` — `IssuedToken issue(DeviceClaims c)` (`record IssuedToken(String token, Instant expiresAt)`), `DeviceClaims verify(String token)` throwing `InvalidDeviceTokenException(reason)` with reasons `malformed|bad_signature|expired|unknown_kid|wrong_issuer`. JWT: header `alg=ES256`, `kid`; claims `iss=reliable-push`, `aud=appId`, `sub=userId`, `did`, `ver`, `iat`, `exp`.
  - `SigningKeys` — loads all configured keys (PEM P-256), `current()` for signing, `byKid(String)` for verification.
  - `TestKeys.newEcPem()` — generates a P-256 private key PEM for tests.
- [x] **Step 1: Failing tests** — `issueThenVerifyRoundTrip()`; `expiredRejected()` (clock +91 d → `expired`); `tamperedRejected()` (`bad_signature`); `oldKidStillValidAfterRotation()` (issue with kid `k1`, reconfigure current `k2` keeping `k1` → verifies; new token header `kid=k2`); `removedKidRejected()` (`unknown_kid`).
- [x] **Step 2: Run** → FAIL. **Step 3: Implement** with `nimbus-jose-jwt` (`ECDSASigner`/`ECDSAVerifier`, clock skew 60 s). **Step 4: Run** → PASS.
- [x] **Step 5: Commit** `feat(backend): device token service with key rotation`

### Task 9: Identity verifiers (OIDC default, signed grant)

**Files:**
- Create: `backend/src/main/java/io/reliablepush/devices/identity/{IdentityVerifier,VerifiedIdentity,OidcIdTokenVerifier,SignedGrantVerifier,IdentityVerifiers}.java`
- Test: `backend/src/test/java/io/reliablepush/devices/identity/OidcIdTokenVerifierIT.java`, `SignedGrantVerifierTest.java`, `backend/src/test/java/io/reliablepush/support/FakeOidc.java`

**Interfaces:**
- Produces:
  - `interface IdentityVerifier { VerifiedIdentity verify(String credential) throws IdentityRejectedException; }`, `record VerifiedIdentity(String userId)`.
  - `IdentityVerifiers.forApp(AppConfig)` → cached verifier per app.
  - `OidcIdTokenVerifier` — JWKS from `jwks-uri` or `<issuer>/.well-known/openid-configuration`, nimbus `JWKSourceBuilder` with cache (TTL 15 min, refresh on unknown kid, outage tolerance); checks `iss`, `aud`, `exp`, signature RS256/ES256; `userId` = configured claim (default `sub`).
  - `SignedGrantVerifier` — HS256 JWT signed with `grant-secret-ref`, max lifetime 5 min, `aud=appId`, `sub=userId`.
  - `FakeOidc` — WireMock-backed issuer: `String issuer()`, `String idToken(String sub, String aud, Duration ttl)`.
- [x] **Step 1: Failing tests** — OIDC: `validTokenYieldsSub()`, `wrongAudienceRejected()`, `expiredRejected()`, `unknownKidTriggersOneJwksRefresh()` (WireMock verifies JWKS fetched exactly twice), `customUserClaim()` (`user-claim: email`). Grant: `validGrant()`, `lifetimeOver5MinRejected()`, `wrongSecretRejected()`.
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): pluggable identity verifiers`

### Task 10: Devices API and device auth filter

**Files:**
- Create: `backend/src/main/java/io/reliablepush/devices/{DeviceController,DeviceService,DeviceRepository,Device,RegisterDeviceRequest,DeviceTokenResponse}.java`, `backend/src/main/java/io/reliablepush/shared/security/{DeviceAuthFilter,DevicePrincipal}.java`
- Test: `backend/src/test/java/io/reliablepush/devices/DeviceApiIT.java`

**Interfaces:**
- Consumes: `DeviceTokenService`, `IdentityVerifiers`, `AppRegistry`, `RateLimiter`.
- Produces:
  - `DeviceAuthFilter` on `/v1/devices/token:refresh`, `/v1/devices/me`, `/v1/events`: `Authorization: Bearer <jwt>` → `DeviceTokenService.verify` + revocation check (`device.revoked_at` null and `token_version` matches; cached 60 s) → `DevicePrincipal(String appId, String userId, UUID deviceId)` (attribute `DevicePrincipal.ATTR`), `rp.app_id`, `rp.device_id`; failures 401 + `auth.failed` WARN.
  - `DeviceService` — `DeviceTokenResponse register(RegisterDeviceRequest r)` (`{appId, idToken, platform: IOS|ANDROID, deviceId?}`; re-registering an existing `deviceId` of the same app+user bumps `token_version`); `DeviceTokenResponse refresh(DevicePrincipal p)`; `void revoke(DevicePrincipal p)`; `boolean isActive(UUID deviceId, int tokenVersion)`.
- [x] **Step 1: Failing tests** — `registerWithValidIdTokenReturns201()` (body `deviceId`, `deviceToken`, `expiresAt` ≈ now+90 d, `device.registered` logged); `registerWithBadIdToken401()`; `unknownApp404()`; `refreshReturnsNewTokenAndOldStillValidUntilExpiry()`; `reRegisterSameDeviceInvalidatesOldToken()` (test config `revocation-cache-ttl: 0s`; after re-registration the old token → 401); `revokeThenRefresh401()`; `deviceRateLimited429()` (`device-per-minute: 2`); `deviceIdOfOtherUserNotHijacked()` (registering another user's `deviceId` creates a new device id).
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): devices api and device auth`

### Task 11: Tracking events API

**Files:**
- Create: `backend/src/main/java/io/reliablepush/tracking/{EventsController,TrackingService,EventRepository,DeviceEvent,EventBatchRequest,EventBatchResult}.java`
- Test: `backend/src/test/java/io/reliablepush/tracking/TrackingServiceTest.java`, `EventsApiIT.java`

**Interfaces:**
- Consumes: `DevicePrincipal`, `NotificationRepository.markDelivered/markRead/findById`, `Clock`.
- Produces: `TrackingService.process(DevicePrincipal p, List<DeviceEvent> events) → EventBatchResult(int accepted, int ignored, List<ItemResult> items)`; `record DeviceEvent(UUID trackingId, EventType type, Instant occurredAt)`; `enum EventType { DELIVERED, READ }`; `ItemResult(trackingId, outcome: ACCEPTED|DUPLICATE|LATE|NOT_FOUND|FORBIDDEN)`.
- [x] **Step 1: Failing tests**
  - `TrackingServiceTest.deliveredSatisfiesNotDeliveredPolicy()` (state `DELIVERED`, `due_at` null); `.deliveredKeepsNotReadPolicyDue()`; `.readImpliesDelivered()` (`delivered_at = read_at` when unset); `.occurredAtClamped()` (future `occurredAt` stored as `received_at`; 30 days old stored as `received_at - 7d`).
  - `EventsApiIT.acceptsBatchAndUpdatesState()`; `.otherUsersTrackingIdForbiddenPerItem()` (item `FORBIDDEN`, other items still `ACCEPTED`, `authz.denied` logged); `.unknownTrackingIdNotFoundPerItem()`; `.duplicateInSameBatchCountedOnce()` (`accepted=1, ignored=1`); `.eventForCancelledIsLate200()` (state stays `CANCELLED`, event row `late=true`); `.eventAfterFallbackClaimedIsLate()`; `.batchOver50Is422()`; `.secondDeviceOfSameUserCounts()`; `.logsTrackingEvents()` (`tracking.event_accepted` with `rp.event_type`, `rp.late`).
- [x] **Step 2: Run** → FAIL. **Step 3: Implement** (one transaction per item; batch result aggregated). **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): tracking events api`

### Task 12: Fallback scheduler (claiming, lease, trigger window)

**Files:**
- Create: `backend/src/main/java/io/reliablepush/fallback/{FallbackScheduler,FallbackDecision,FallbackDispatcher}.java`, `backend/src/main/java/io/reliablepush/fallback/WorkerConfig.java` (`@Profile("worker")`, `@EnableScheduling`)
- Test: `backend/src/test/java/io/reliablepush/fallback/FallbackDecisionTest.java`, `FallbackSchedulerIT.java`

**Interfaces:**
- Consumes: `NotificationRepository.claimDue/reschedule/close`, `AppRegistry`, `TimeScale`, `Clock`, `WebhookClient` (Task 13 — define the interface here: `interface WebhookClient { WebhookResult send(AppConfig app, FallbackCall call); }`, `record FallbackCall(UUID trackingId, String userId, JsonNode fallback, String reason, int attempt)`, `sealed interface WebhookResult permits Success, Retryable, Final`).
- Produces:
  - `FallbackDecision` (pure) — `static Outcome decide(Instant now, Instant dueAt, Duration window, int attemptsSoFar, int maxAttempts, List<Duration> backoff, WebhookResult lastResult)` → `DISPATCH | EXPIRE | RETRY_AT(Instant) | FAIL | DONE`; rule: dispatch only if `now <= dueAt + window`; next retry at `now + backoff[attempts-1]` only if that instant `<= dueAt + window`, else `EXPIRE`.
  - `FallbackScheduler.tick()` — `@Scheduled(fixedDelayString = "${reliable-push.worker.poll-interval:5s}")`; claims ≤ batch rows (lease 2 min), sets `ThreadContext trace.id` (new) + `rp.tracking_id` per row, logs `fallback.claimed` (or `fallback.reclaimed` when the row was already `DISPATCHING`), applies `FallbackDecision`, calls `WebhookClient`, logs `fallback.dispatched|retry_scheduled|failed|expired`, increments metrics (Task 15 wires counters; here use `MeterRegistry` directly).
- [x] **Step 1: Failing tests**
  - `FallbackDecisionTest`: `dispatchesInsideWindow()`, `expiresWhenClaimedAfterWindow()`, `retryScheduledWithBackoff()` (attempt 1 failed at `due+0` → retry at `due+1m`), `retryBeyondWindowExpires()` (window 5 min, attempt 3 failed at `due+3m` → `EXPIRE`), `finalFailureAfterMaxAttempts()`.
  - `FallbackSchedulerIT` (fake `WebhookClient` bean, test clock, `minute-duration: 1s` under profile `e2e`): `notDeliveredFiresExactlyOnce()`; `deliveredBeforeDueNeverFires()`; `notReadPolicyFiresWhenOnlyDelivered()`; `twoConcurrentWorkersNeverDoubleFire()` (200 due rows, two `tick()` threads in parallel → fake client received each `trackingId` exactly once); `crashedWorkerRowReclaimedAfterLeaseInsideWindow()` (row left `DISPATCHING` with expired lease → re-dispatched once, `fallback.reclaimed` logged); `crashedWorkerRowOutsideWindowExpires()` (webhook not called, state `FALLBACK_EXPIRED`); `outageLongerThanWindowExpires()` (PENDING row due 30 min ago → `FALLBACK_EXPIRED`); `deliveredDuringDispatchStaysTriggered()` (event arriving after claim → `late`, final state `FALLBACK_TRIGGERED`).
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): fallback scheduler with lease and trigger window`

### Task 13: Webhook client (HMAC, retries classification)

**Files:**
- Create: `backend/src/main/java/io/reliablepush/fallback/{HttpWebhookClient,WebhookSigner}.java`
- Test: `backend/src/test/java/io/reliablepush/fallback/WebhookSignerTest.java`, `HttpWebhookClientIT.java`

**Interfaces:**
- Consumes: `WebhookClient`, `FallbackCall`, `WebhookResult` (Task 12).
- Produces: `WebhookSigner.sign(byte[] secret, long epochSeconds, byte[] body) → "v1=" + hex(HMAC-SHA256(secret, epochSeconds + "." + body))`. `HttpWebhookClient` (JDK `HttpClient`, redirects `NEVER`, connect + request timeout = app `http-timeout` 10 s): POST JSON `{trackingId, userId, fallback, reason, attempt}`; headers `Content-Type: application/json`, `X-RP-Timestamp`, `X-RP-Signature`, `Idempotency-Key: <trackingId>`; result `Success` for 2xx, `Retryable` for 408/429/5xx/timeout/IO error/3xx, `Final` for other 4xx.
- [x] **Step 1: Failing tests** — `WebhookSignerTest.knownVector()` (secret `"s3cr3t"`, ts `1700000000`, body `{"a":1}` → assert against value computed in the test with `javax.crypto.Mac` — not hard-coded by hand); `HttpWebhookClientIT` (WireMock): `success2xx()` (headers present, signature verifies), `server500Retryable()`, `badRequest400Final()`, `tooMany429Retryable()`, `redirect302RetryableNotFollowed()` (WireMock target of redirect receives 0 requests), `slowReceiverTimesOut()` (fixed delay 12 s, test timeout config 1 s → `Retryable`, returns in < 3 s), `noSecretOrBodyInLogs()`.
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): signed webhook client`

### Task 14: Retention job

**Files:**
- Create: `backend/src/main/java/io/reliablepush/notifications/RetentionJob.java` (`@Profile("worker")`, daily `@Scheduled(cron = "0 17 3 * * *")`, uses `NotificationRepository.purgeClosedBefore` in chunks of 1,000)
- Test: `backend/src/test/java/io/reliablepush/notifications/RetentionJobIT.java`
- [x] **Step 1: Failing test** `purgesOnlyClosedOlderThanRetention()` — rows: closed 31 d ago (deleted, with its events), closed 29 d ago (kept), PENDING created 60 d ago (kept); `retention.purged` logged with `count=1`.
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [x] **Step 5: Commit** `feat(backend): retention job`

### Task 15: Metrics

**Files:**
- Create: `backend/src/main/java/io/reliablepush/shared/metrics/RpMetrics.java`
- Modify: `NotificationService`, `TrackingService`, `FallbackScheduler`, `HttpWebhookClient` to use it.
- Test: `backend/src/test/java/io/reliablepush/shared/metrics/MetricsIT.java`

**Interfaces:**
- Produces: `RpMetrics` — `notificationRegistered()`, `event(EventType type, boolean late)`, `fallback(String outcome)` (`triggered|failed|expired`), `Timer webhookLatency()`, gauge `rp_due_backlog` (count of rows with `due_at <= now()`, refreshed every 30 s by worker).
- [x] **Step 1: Failing test** `exposesSpecMetrics()` — after one register, one DELIVERED event and one triggered fallback, `/actuator/prometheus` contains `rp_notifications_registered_total 1.0`, `rp_events_total{late="false",type="DELIVERED"} 1.0`, `rp_fallback_total{outcome="triggered"} 1.0`, `rp_webhook_latency_seconds_count`, `rp_due_backlog`.
- [x] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw verify` → PASS.
- [x] **Step 5: Commit** `feat(backend): prometheus metrics`

### Task 16: Container image

**Files:**
- Create: `backend/Dockerfile`, `backend/.dockerignore`, `backend/src/test/java/io/reliablepush/ContainerImageIT.java` (tag `@Tag("image")`, excluded from default `verify`; run with `-Pimage`)
- [ ] **Step 1: Write failing test** `ContainerImageIT.startsAsArbitraryNonRootUid()` — Testcontainers `GenericContainer` from the built image with `withCreateContainerCmdModifier(cmd -> cmd.withUser("1000870000:0"))`, Postgres network alias, env config for one app → `/actuator/health/readiness` 200 within 60 s; image size < 250 MB (`docker image inspect` via Testcontainers `DockerClient`).
- [ ] **Step 2: Run** `./mvnw verify -Pimage` → FAIL (no image).
- [ ] **Step 3: Dockerfile** — multi-stage: build with `eclipse-temurin:21-jdk` (`./mvnw -DskipTests package`, layertools extract); runtime `eclipse-temurin:21-jre-alpine` (or `ubi9/openjdk-21-runtime`), `USER 1001`, files group-owned by `0` with `g=u` permissions (OpenShift arbitrary UID), `EXPOSE 8080`, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`, no shell entrypoint scripts. The `-Pimage` Maven profile builds the image with `docker build` (exec plugin) before failsafe.
- [ ] **Step 4: Run** `./mvnw verify -Pimage` → PASS.
- [ ] **Step 5: Commit** `build(backend): openshift-compatible container image`

### Task 17: CI pipeline

**Files:**
- Create: `.github/workflows/backend-ci.yml`, `.github/workflows/codeql.yml`, `.github/dependabot.yml`, `.github/pull_request_template.md`
- [ ] **Step 1: Write workflows.** `backend-ci.yml`: on `push` (branches `develop`, `main`, `release/**`, `hotfix/**`) and `pull_request`; paths `backend/**`, `.github/workflows/backend-ci.yml`; jobs: `build` (temurin 21, Maven cache, `./mvnw -B verify`, upload JaCoCo + surefire/failsafe reports), `image` (needs build; `./mvnw -B verify -Pimage`; Trivy `aquasecurity/trivy-action` on the image, fail on CRITICAL/HIGH), `dependency-review` (PRs only, `actions/dependency-review-action`). All actions pinned to full commit SHAs; `permissions: contents: read` by default. `codeql.yml`: Java, weekly + PR. Dependabot: maven (`/backend`), github-actions, weekly. PR template: summary, linked requirement ids, checklist (tests, review-loop result, **E2E local result line**, docs updated).
- [ ] **Step 2: Validate** — Run: `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:latest` → no errors.
- [ ] **Step 3: Commit** `ci: backend build, image scan, codeql, dependabot`

### Task 18: Backend documentation and handoff

**Files:**
- Create: `docs/architecture.md` (modules, lifecycle diagram, claiming/lease/window explained), `docs/api.md` (endpoint reference generated by hand from `openapi.yaml`, examples with curl, error codes), `docs/integration-guide.md` (sender integration: register → embed `rp_tid` → payload shapes of spec §6 for APNs, FCM v1, FCM v2; fallback receiver: verify `X-RP-Signature`, de-duplicate on `Idempotency-Key`, sample verification code in Java), `docs/security.md` (threat model summary, auth flows, key rotation procedure, logging rules), `docs/operations.md` (configuration reference §8, profiles `api`/`worker`, env vars, health/metrics, log format and event catalogue, retention, scaling), `docs/testing.md` (test levels, how to run `./mvnw verify` and `-Pimage`; E2E/console sections marked "arrives with plan 1B")
- Modify: `AGENTS.md` (Commands table: backend build/test/image commands), `docs/STATUS.md` (new entry), `README.md` (quick start for the backend with docker run + example config)
- [ ] **Step 1: Write docs** — every config key and log event in docs must exist in code: Run `grep -o 'event.action[^`]*' -r backend/src/main | sort -u` and compare with `docs/operations.md` catalogue.
- [ ] **Step 2: Verify links** — every relative link in `README.md` and `docs/*.md` resolves (`grep -o '](\.\{0,2\}/\?[^)#]*' ...` + `test -e`).
- [ ] **Step 3: Run the `review-loop` skill** over `git diff <cycle start>...HEAD` for the whole backend; fix high/medium findings.
- [ ] **Step 4: Final verification** — `cd backend && ./mvnw verify && ./mvnw verify -Pimage` → BUILD SUCCESS; record coverage % in `docs/STATUS.md`.
- [ ] **Step 5: Commit** `docs: backend architecture, api, integration, security, operations, testing`; then run the `session-handoff` skill.
