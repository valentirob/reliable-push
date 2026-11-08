# Sidecar Integration Revision (cycle 6, 0.2.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the sidecar cheap and safe for the existing sender. The work has five parts:
- a Java 8 payload jar with sender-generated tracking ids;
- `NO_DEVICE_REGISTERED` for users without a device;
- no token renewal on the push receive path, plus an app-driven `ensureRegistered`;
- a 90-day grace period for expired tokens on events;
- display that never depends on tracking, on both platforms.

**Architecture:**
- New Maven module `payload-java/`, Java 8, zero dependencies.
- Backend: changes in `notifications`, `devices` and `shared/security`, plus one Flyway migration `V7__sidecar_revision.sql` (V6 is the circuit breaker).
- SDKs: the refresh logic moves out of both `EventSender`s into `ensureRegistered`; receive paths get catch-all guards.
- Harness: uses the jar and gains four E2E scenarios.

**Tech Stack:** Java 8 (payload jar), Java 17 (client-java), Java 21 + Spring Boot 4 (backend, harness), Swift 5.9+ / iOS 15 (SPM), Kotlin / minSdk 26 (Android), JUnit 5, AssertJ, Testcontainers, XCTest, Robolectric/JUnit (Android unit), Playwright.

**Spec:** [`docs/specs/2026-11-08-sidecar-integration-revision-design.md`](../specs/2026-11-08-sidecar-integration-revision-design.md) (approved 2026-11-08). `§n` below refers to it.

## Global Constraints

- Branch `feature/sidecar-integration-revision`; Conventional Commits; trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Environment: `JAVA_HOME=/opt/homebrew/opt/openjdk@21`, its `bin` first on `PATH`, `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. A JDK 8 for the payload jar's second test run: use one already installed (`/usr/libexec/java_home -V`); if none is installed, CI covers JDK 8 and the local run uses JDK 21 only. Never install software without the maintainer's OK.
- **No backward compatibility** (nothing is deployed; maintainer decision 2026-11-08):
  - **Ruling:** `trackingId` is **required** in `POST /v1/notifications`. The spec's server-generated fallback is dropped; Task 2 updates the spec text accordingly.
  - `device.token_expires_at` is `not null`, backfilled in the migration.
- Exact names:
  - state `NO_DEVICE_REGISTERED`;
  - per-app setting `device-token-events-grace` (days, default 90, 0 disables);
  - log events `notification.no_device_registered` (INFO), `sdk.internal_error` (SDK WARN);
  - metrics `rp_notification_registration_seconds` and `rp_notifications_registered_total{outcome="no_device_registered"}`;
  - `rp_events_total` gains tag `token` = `valid` | `grace`;
  - `RegisterResult` cases `notLoggedIn`, `alreadyRegistered`;
  - iOS `extensionSendTimeout` default 2 s.
- Payload limits: APNs 4,096 bytes, FCM 4,096 bytes; reserved keys `rp_tid` (all), `title`/`body` (FCM data). The validation codes are exactly §4.3.
- New backend log events go into the event table of `docs/operations.md` in the task that adds them. Every API change is in `openapi.yaml` and checked with `OpenApiAssert.assertMatchesContract`.
- Verification per component:
  - backend: `cd backend && ./mvnw -B verify`;
  - payload jar and client: `./mvnw -B -pl payload-java,client-java verify`;
  - harness: `./mvnw -B -pl harness -am verify`;
  - iOS: `xcodebuild test -scheme ReliablePush -destination "platform=iOS Simulator,name=iPhone 17"` and `ios/scripts/size.sh`;
  - Android: `cd android && ./gradlew build`.
- English everywhere; docs-sync rule; Task 8 does the cross-cutting docs.
- Cycle 5 (fallback circuit breaker) is merged: `V6` exists, the backend tests run with the breaker disabled by default (`application-test.yml`), and the harness has a dedicated `circuit` app.

## Review Focus

1. A sender retries registration with the same `trackingId` while the first request is still committing → one row, both `201`, same state (Task 2 IT, concurrent).
2. A user whose only device was revoked, or whose token expired beyond the grace period → `NO_DEVICE_REGISTERED`; a device registered one second later does not reopen the row (Task 2 IT).
3. A token expired exactly at the grace boundary, on `/v1/events` vs on `token:refresh` → accepted vs `401` (Task 3 unit).
4. The extension gets a `401` → no refresh call is made, the event stays queued, and the next `ensureRegistered` refreshes and flushes it (Tasks 5 and 6).
5. Android `handle` when the token store throws, the queue write throws and the poster throws once → a notification is still posted, no exception escapes, and `DELIVERED` is on disk before the post (Task 6).

---

### Task 1: Payload jar `payload-java`

**Files:**
- Create: `payload-java/pom.xml` (artifact `io.reliablepush:reliable-push-payload`, `maven.compiler.release` 8, test deps JUnit 5 + AssertJ only, same plugin set as `client-java/pom.xml`); `payload-java/src/main/java/io/reliablepush/payload/{TrackingIds,ApnsPayloads,FcmPayloads,PayloadException}.java`; `payload-java/src/main/java/io/reliablepush/payload/internal/{Json,JsonWriter}.java` (Java 8 parser/writer adapted from `client-java/.../internal/Json.java`); `payload-java/src/test/java/io/reliablepush/payload/*Test.java`; `payload-java/src/test/resources/vectors/*.json`; `payload-java/README.md`
- Modify: root `pom.xml` (module `payload-java` first); `.github/workflows/harness-ci.yml` (job: `payload-java` verify on Temurin 8 and 21, path filter `payload-java/**`)

**Interfaces:**
- Produces: exactly the API of §4.2 (signatures verbatim), `PayloadException.Code` = the eight codes of §4.3.

- [ ] **Step 1: Write the failing tests**
  - `TrackingIdsTest`: `newId` is a lower-case v4 UUID; `isValid` rejects upper-case-free garbage, accepts any UUID.
  - `ApnsPayloadsTest`:
    - `build` output is exactly `{"aps":{"alert":{"title":"T","body":"B"},"mutable-content":1},"rp_tid":"<tid>"}` plus custom keys;
    - `decorate` keeps the sender's keys and order and appends `mutable-content`/`rp_tid`;
    - each of `APNS_NO_ALERT`, `RESERVED_KEY`, `APNS_TOO_LARGE`, `INVALID_JSON`, `INVALID_TRACKING_ID`.
  - `FcmPayloadsTest`:
    - `build` equals the integration guide's v1 example;
    - `data` returns `rp_tid`/`title`/`body` plus custom keys;
    - `decorate` moves `notification.title/body` into data, removes `notification`, sets `android.priority` `HIGH`;
    - each of `FCM_NON_STRING_DATA`, `FCM_UNSUPPORTED_NOTIFICATION_FIELD` (the message names the fields), `FCM_TOO_LARGE`, `RESERVED_KEY`.
  - `GoldenVectorsTest` runs every file in `vectors/` (input → exact output or error code); at least 12 vectors covering every operation and every code.
- [ ] **Step 2: Run** `./mvnw -B -pl payload-java verify` — FAIL.
- [ ] **Step 3: Implement.** No dependency, no reflection, immutable; compact JSON with keys in input order.
- [ ] **Step 4: Run** the same command on JDK 21, then on JDK 8 if one is installed — PASS.
- [ ] **Step 5: Commit** `feat(payload): Java 8 payload library with golden vectors`.

### Task 2: Backend — sender-supplied tracking id and `NO_DEVICE_REGISTERED`

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__sidecar_revision.sql`:
  - state check gains `NO_DEVICE_REGISTERED`;
  - `alter table device add column token_expires_at timestamptz`, backfill `last_seen_at + interval '90 days'`, then `set not null`.
- Modify:
  - `notifications/NotificationController.java`, `NotificationService.java`, `NotificationRepository.java` (insert with the given id; idempotent per app on `id`, `409` across apps and on an `id`/`externalId` mismatch);
  - the request record (`trackingId` required, UUID);
  - `devices/DeviceRepository.java` (`token_expires_at` on register and refresh; `boolean hasActiveDevice(String appId, String userId, Instant now, Duration grace)`);
  - the notification state enum and the admin dashboard filters/counts (`admin/AdminQueries.java`, `OverviewQuery.java`, page `search.js` state list);
  - `shared/metrics/RpMetrics.java`;
  - `openapi.yaml`;
  - spec §2.1 text (the ruling: `trackingId` required; delete the "If absent" sentence).
- Test: `backend/src/test/java/io/reliablepush/notifications/NotificationRegistrationIT.java` (new) and update `NotificationApiIT` and every IT that registers notifications (they must now send `trackingId` and register a device first where they expect a fallback).

**Interfaces:**
- Produces:
  - `POST /v1/notifications` body `{trackingId (required, uuid), userId, externalId?, policy?, timeoutMinutes?, fallback}`; `201` with `state` = `PENDING` or `NO_DEVICE_REGISTERED`; `409` problem on conflicts;
  - `DeviceRepository.hasActiveDevice`;
  - an `apps` per-app setting accessor for `device-token-events-grace`, added here because Task 3 uses it: `AppConfig.deviceTokenEventsGrace()` as `Duration`.

- [ ] **Step 1: Write the failing IT**
  - `registersWithGivenTrackingId`;
  - `sameTrackingIdSameAppIsIdempotent`, including a concurrent pair (two threads, one row);
  - `sameTrackingIdOtherAppIs409`; `trackingIdExternalIdMismatchIs409`; `missingTrackingIdIs400`;
  - `noActiveDeviceStoresNoDeviceRegistered` (state final, `due_at` null, worker never calls the webhook, log line, metric);
  - `revokedOnlyDeviceMeansNoDevice`; `expiredBeyondGraceMeansNoDevice`; `deviceInsideGraceCountsActive`;
  - `cancelOfNoDeviceRowIsUnchanged`; `eventForNoDeviceRowIsLate`;
  - `migrationBackfillsTokenExpiresAt`;
  - all responses through `OpenApiAssert`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Update the existing ITs and test helpers to send `trackingId` and register a device.
- [ ] **Step 4: Run** `cd backend && ./mvnw -B verify` — PASS. Add `notification.no_device_registered` to `docs/operations.md`.
- [ ] **Step 5: Commit** `feat(backend): sender tracking id and NO_DEVICE_REGISTERED`.

### Task 3: Backend — grace period for expired device tokens

**Files:**
- Modify: `devices/DeviceTokenService.java` (verify with an allowed-expiry parameter), `shared/security/DeviceAuthFilter.java` (grace only for `POST /v1/events` and `DELETE /v1/devices/me`), `DevicePrincipal.java` (`boolean inGrace()`), `tracking/TrackingService.java` (log field `token_state`, metric tag), `apps` configuration (`device-token-events-grace`, validation ≥ 0), `application.yml`, `openapi.yaml` (401 descriptions)
- Test: `DeviceTokenServiceTest` (boundaries), `backend/src/test/java/io/reliablepush/tracking/EventsGraceIT.java`

**Interfaces:**
- Consumes: Task 2's `AppConfig.deviceTokenEventsGrace()`.

- [ ] **Step 1: Write the failing tests**
  - unit:
    - expired by `grace - 1s` is accepted only with the grace allowed;
    - expired by `grace + skew + 1s` is rejected;
    - `grace` 0 behaves as today;
  - IT:
    - events with a grace token are `200` and counted `token="grace"`;
    - logout with a grace token is `204`;
    - `token:refresh` with a grace token is `401`;
    - events past the grace period are `401`;
    - a revoked device is still `401` inside the grace period.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `cd backend && ./mvnw -B verify` — PASS.
- [ ] **Step 5: Commit** `feat(backend): grace period for expired device tokens on events`.

### Task 4: `client-java`

**Files:**
- Modify: `client-java/src/main/java/io/reliablepush/client/RegisterRequest.java` (`trackingId` required in the builder, validated as UUID), `NotificationState.java` (`NO_DEVICE_REGISTERED`), `SenderClient.java` (serialises it), `client-java/README.md` (1 s timeout recommendation, use with the payload jar)
- Test: existing `client-java` tests updated plus `registerSendsTrackingId`, `missingTrackingIdFailsFast`, `parsesNoDeviceRegistered`

- [ ] **Step 1: Write the failing tests.**
- [ ] **Step 2: Run** `./mvnw -B -pl client-java verify` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(client): tracking id and NO_DEVICE_REGISTERED`.

### Task 5: iOS SDK

**Files:**
- Modify:
  - `ios/Sources/ReliablePush/Internal/EventSender.swift` (no refresh; `401` keeps the event and sets `renewalNeeded`);
  - delete `Internal/RefreshGate.swift` and `RefreshGateTests.swift`;
  - `Internal/SDK.swift` and `Internal/TokenStore.swift` (`renewalNeeded` stored in App Group storage);
  - `ReliablePush.swift` (`ensureRegistered` both forms, §3.2 decision order, single run at a time);
  - `RegisterResult.swift` (`notLoggedIn`, `alreadyRegistered`);
  - `ReliablePushOptions.swift` (default 2 s);
  - `ReliablePushExtension.swift` (order of §3.3; every SDK call that can fail handled so the content handler is called once, at once);
  - the iOS sample app (calls `ensureRegistered` at launch and after login);
  - `ios/README.md`.
- Test: `EventSenderTests` (no refresh on any status), `ReliablePushTests` (`ensureRegistered` branches), `ReliablePushExtensionTests` (fault injection: Keychain read fails, queue file corrupt, App Group unavailable, settings read hangs, network error → handler exactly once within `timeout + 0.5 s`), `SDKTestSupport` helpers

**Interfaces:**
- Produces: the §3.2 Swift signatures verbatim.

- [ ] **Step 1: Write the failing tests**
  - `ensureRegistered`:
    - no token → provider called → register;
    - provider `nil` → `notLoggedIn`, no call;
    - valid token → `alreadyRegistered`, no network;
    - last 20 % → refresh;
    - `renewalNeeded` → refresh, then flag cleared and queue flushed;
    - refresh `401` → ID-token path;
    - expired → ID-token path;
    - two concurrent calls → one network sequence.
  - Extension:
    - default timeout is 2 s;
    - a `401` makes no refresh request (`StubURLProtocol` sees only `/v1/events`);
    - each fault-injection case.
- [ ] **Step 2: Run** the iOS test command — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the tests and `ios/scripts/size.sh` (under 307,200 bytes) — PASS. Then run `./scripts/ios-e2e.sh` and expect `iOS E2E: 8/8 passed, 1 skipped`; update the E2E test if its refresh case moved.
- [ ] **Step 5: Commit** `feat(ios): no token renewal on receive, ensureRegistered, robust extension`.

### Task 6: Android SDK

**Files:**
- Modify:
  - `android/reliablepush/src/main/kotlin/io/reliablepush/android/internal/EventSender.kt` (no refresh; `401` keeps the event and sets `renewalNeeded`);
  - delete `internal/RefreshGate.kt` and `RefreshGateTest.kt`;
  - `internal/Sdk.kt`, `internal/TokenStore.kt` (`renewalNeeded`);
  - `internal/NotificationPoster.kt` (`canShow()` split out; `postMinimal`);
  - `ReliablePush.kt` (`ensureRegistered`; `handle` order and catch-all of §3.4);
  - `RegisterResult.kt` (`NotLoggedIn`, `AlreadyRegistered`);
  - `internal/RetryJobService.kt` (no refresh);
  - the sample app (`ensureRegistered` at start and after login);
  - `android/README.md` if present.
- Test: `ReliablePushTest` (`handle` order with an injected failing token store, queue and poster; no exception escapes; `DELIVERED` persisted before the post), `EventSenderTest`, a new `EnsureRegisteredTest`, `NotificationPosterTest`

**Interfaces:**
- Produces: `fun ensureRegistered(idTokenProvider: (deliver: (String?) -> Unit) -> Unit, callback: ((RegisterResult) -> Unit)? = null)`.
  - **Ruling:** the SDK has no coroutines dependency, and NFR-10 forbids adding one, so the spec's `suspend` provider becomes a callback. The provider calls `deliver` exactly once, from any thread.
  - If `deliver` is not called within 30 s, the run ends `NotLoggedIn`.
  - Task 6 updates spec §3.2 to this signature.

- [ ] **Step 1: Write the failing tests**
  - the same `ensureRegistered` matrix as Task 5;
  - `handle`:
    - order (gate → persist → post → send);
    - each injected failure;
    - minimal post when the full post path throws;
    - returns `false` only when the minimal post also fails;
    - the post fails after persist → event kept.
  - The retry job never calls refresh.
- [ ] **Step 2: Run** `cd android && ./gradlew build` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS. Then `./scripts/android-e2e.sh` and expect `Android E2E: 8/8 passed, 1 skipped`.
- [ ] **Step 5: Commit** `feat(android): no token renewal on receive, ensureRegistered, hardened handle`.

### Task 7: Harness and E2E

**Files:**
- Modify:
  - `harness/pom.xml` (depend on `payload-java`);
  - `harness/.../sender/PayloadPreview.java` (APNs and FCM v1 through the jar; v2 stays);
  - `FakeSender.java`, `SendPushCommand.java` (generate the id with `TrackingIds.newId()`, register with `trackingId`);
  - `devices/SimulatedDevice.java`, `DeviceSimulator.java` (no refresh on the event path; an explicit `ensureRegistered` action);
  - the console views that show states (`NO_DEVICE_REGISTERED`);
  - existing scenarios that relied on refresh during events (`TokenRefreshNearExpiry` now drives `ensureRegistered`).
- Create: `harness/.../scenarios/catalog/{NoDeviceRegistered,ClientTrackingId,RegistrationLost,TokenGrace}.java` per spec §6.
- Test: harness unit tests for `PayloadPreview` against the jar output.

- [ ] **Step 1: Write the scenarios and the unit test.**
- [ ] **Step 2: Run** `./mvnw -B -pl harness -am verify`, then `./scripts/e2e.sh` — expect `[e2e] summary: 23/23 passed` (19 after cycle 5, plus 4). Note: the E2E stack trips any app's circuit at 10 unmet fallbacks in 30 s; send bursts to a dedicated app.
- [ ] **Step 3: Fix** until green; also run the console smoke (`-Pconsole-smoke`).
- [ ] **Step 4: Commit** `test(harness): payload jar, tracking id and grace scenarios`.

### Task 8: Docs, diagrams, presentation

**Files:**
- Modify:
  - every file of spec §10;
  - `docs/requirements.md` (§9 changes; FR-20..FR-24);
  - `README.md` (component list, payload jar);
  - `AGENTS.md` (components table row and commands row for `payload-java`);
  - `docs/testing.md` (new scenarios, counts, payload jar command);
  - `docs/overview.md` diagrams;
  - at-a-glance SVG and PNG;
  - `docs/presentation.html`;
  - `CHANGELOG.md`, `docs/TODO.md` (2.1 mentions the jar), `docs/STATUS.md`.
- Test: the harness `log-catalogue` scenario (from Task 7's full E2E run) and a link check by reading every changed doc's links.

- [ ] **Step 1: Write** the integration guide around spec §1: the checklist, the outcome table, the residual case, and the examples with raw APNs, Firebase Admin SDK and Pushy.
- [ ] **Step 2: Update** the SDK guides (iOS extension step by step; Android data message → local notification, the market standard, robustness, `AndroidConfigV2` status) and the rest of §10.
- [ ] **Step 3: Regenerate** the PNG from the SVG; check that the presentation shows the new flow.
- [ ] **Step 4: Commit** `docs: sidecar integration revision`.
