# System overview

This page explains reliable-push with diagrams. It starts from the big picture and then shows each component in detail.
The reference documents linked from each section have the full contracts and settings. The diagrams are written in
[Mermaid](https://mermaid.js.org/), which GitHub renders in place.

For a slide-style version to present the project, open [presentation.html](presentation.html) in a browser.

![reliable-push at a glance](images/reliable-push-at-a-glance.png)

The picture above is the whole system on one page: ① your backend generates a tracking id with the payload jar and
registers the push with reliable-push (1 s timeout), ② sends it through FCM / APNs whatever the registration answered, ③ the phone receives it, ④ the tracking module reports `DELIVERED` and `READ`, ⑤ when the
timeout passes without the policy being met the worker calls your signed webhook, and ⑥ your fallback service sends
the SMS or email. Before the worker claims anything, the per-app **circuit breaker** checks that delivery tracking still
works: when an app's fallbacks come due while almost none of its pushes were reported delivered, it stops that app's
fallbacks instead of firing a storm of needless SMS ([§4](#4-backend)). The small "Dashboard /admin" box in the backend is
the ops dashboard ([§5](#5-ops-dashboard)), read-only except the fallback resume. The editable source is [images/reliable-push-at-a-glance.svg](images/reliable-push-at-a-glance.svg).

## 1. The sidecar principle

reliable-push is a **sidecar**. It watches your push notifications and acts only when one is not confirmed in time. It
never sends a push, never holds your FCM or APNs credentials, and is not on the path that delivers a notification.

```mermaid
flowchart TB
  subgraph main["Your push path: unchanged"]
    BE["Your backend"] -- "push with your FCM / APNs credentials" --> PS["FCM / APNs"]
    PS --> DEV["Device: the notification is shown"]
  end
  subgraph side["reliable-push: the sidecar next to it"]
    RP["reliable-push backend<br/>(tracking + fallback)"]
    FR["Your fallback receiver<br/>(SMS, email, ...)"]
    RP -. "③ signed webhook, only if unconfirmed in time" .-> FR
  end
  BE -. "① register with its own trackingId (1 s timeout)" .-> RP
  DEV -. "② SDK reports DELIVERED / READ" .-> RP
```

Solid arrows are your existing push path. Dotted arrows are what reliable-push adds.

| If this fails... | ...your pushes | ...and reliable-push |
|---|---|---|
| The reliable-push backend is down or slow | **Still sent and shown.** Your backend registers with a short timeout (1 s) and sends the push with `rp_tid` whatever the answer; a registration that was not stored only means no fallback for that push ([integration-guide.md](integration-guide.md#1-register-the-notification)). | Tracking pauses. Device events stay queued on the device and are sent when the backend is back. Due fallbacks are sent late, within the trigger window, or expire. |
| The device is offline when the push arrives | Delivered by FCM / APNs when the device is back, as today. | `DELIVERED` is queued on the device and sent later. If the policy is not met by the timeout, the fallback fires. |
| The SDK cannot reach the backend | **Still shown.** iOS hands the notification back after at most `extensionSendTimeout` (2 s by default); Android posts it before the send, which runs in the background. Errors never reach the app. | The event stays in the SDK's on-disk queue and is retried. |
| The SDK fails internally | **Still shown.** iOS hands back the best content so far at once; Android posts the simplest notification it can. | Nothing or `DELIVERED` recorded, depending on where it failed; the fallback decides. |
| The user has no registered device | Sent as usual. | Stored as `NO_DEVICE_REGISTERED`: not tracked, never a fallback. |
| A push has no `rp_tid` | Untouched: the SDK ignores it and your own code handles it. | Not tracked. |
| The fallback receiver is down | Not affected. | The webhook is retried with backoff inside the trigger window, then the notification ends as `FALLBACK_FAILED` or `FALLBACK_EXPIRED`. |

On iOS the Notification Service Extension records `DELIVERED`, sends it once and hands the notification to the system
when the send ends, at most `extensionSendTimeout` (2 s by default) later. On Android the backend sends a data-only
message and the SDK posts the notification itself (the market-standard model, [android.md](android.md)); `DELIVERED`
is persisted just before the post and sent on the SDK's own thread. Neither path renews the device token: only the
app's `ensureRegistered` does.

## 2. Components and how they relate

```mermaid
flowchart TB
  SB["Your sender backend<br/>(+ payload jar, client-java: optional)"]
  PP["FCM / APNs"]
  APP["Your mobile app<br/>+ reliable-push SDK"]
  RCV["Your fallback receiver<br/>(HTTPS endpoint)"]
  IDP["Your identity provider<br/>(OIDC)"]
  subgraph rp["reliable-push"]
    API["Backend: api role<br/>REST /v1/*"]
    DB[("PostgreSQL")]
    WRK["Backend: worker role<br/>fallback scheduler"]
    API --> DB
    WRK --> DB
  end
  SB -- "push with rp_tid" --> PP
  PP -- "push arrives" --> APP
  SB -- "register (X-Api-Key)" --> API
  APP -- "devices, events (device JWT)" --> API
  API -. "verify ID token (JWKS)" .-> IDP
  WRK -- "signed webhook (HMAC)" --> RCV
```

| Component | Path | What it does | Detail |
|---|---|---|---|
| Backend | `backend/` | Stores notifications, receives device events, triggers fallbacks | [§4](#4-backend) |
| Ops dashboard | `backend/` (module `admin`) | Web page and API for support, ops and product, read-only except the fallback resume | [§5](#5-ops-dashboard) |
| Java sender client | `client-java/` | Optional zero-dependency client for the sender API | [§6](#6-java-sender-client-and-payload-jar) |
| Payload jar | `payload-java/` | Optional zero-dependency Java 8+ library: tracking ids, tracked APNs / FCM payloads, golden vectors | [§6](#6-java-sender-client-and-payload-jar) |
| Android SDK | `android/` | Reports `DELIVERED` / `READ` from an Android app | [§7](#7-android-sdk) |
| iOS SDK | `ios/`, `Package.swift` | Reports `DELIVERED` / `READ` from an iOS app and its extension | [§8](#8-ios-sdk) |
| Harness | `harness/` | Local test stack: simulators, fake receiver, web console, E2E | [§9](#9-harness-and-web-console) |
| Helm chart | `deploy/helm/` | Installs the backend on Kubernetes / OpenShift | [§10](#10-helm-chart-and-deployment) |
| Load tests | `loadtest/` | Gatling load tests, run locally | [§11](#11-load-tests) |

## 3. One notification, end to end

```mermaid
sequenceDiagram
  autonumber
  participant SB as Sender backend
  participant RP as reliable-push (api)
  participant PP as FCM / APNs
  participant SDK as App + SDK
  participant W as reliable-push (worker)
  participant FR as Fallback receiver
  Note over SB: tid = TrackingIds.newId(), payload built with tid
  SB->>RP: POST /v1/notifications {trackingId: tid, userId, policy, timeoutMinutes, fallback} (timeout 1 s)
  RP-->>SB: 201 {state: PENDING, dueAt} (informative only)
  SB->>PP: send push with rp_tid = tid, always
  PP->>SDK: push arrives (also when the app is not running)
  SDK->>RP: POST /v1/events DELIVERED
  Note over SDK: the user taps the notification
  SDK->>RP: POST /v1/events READ
  Note over RP: policy satisfied: no fallback
  alt not confirmed by dueAt
    W->>RP: circuit check: is the app's delivery tracking working?
    W->>RP: claim the due notification (PostgreSQL row lock)
    W->>FR: signed webhook {trackingId, userId, fallback, reason}
    FR-->>W: 2xx
    Note over W: FALLBACK_TRIGGERED
  end
```

If the user has no active device the answer is `201` with state `NO_DEVICE_REGISTERED` and nothing else happens. If
the registration fails or times out the push is still sent: a row that was stored is tracked as usual (no duplicate
fallback), one that was not means no fallback. The outcome table is in
[integration-guide.md](integration-guide.md#1-register-the-notification).

The policy decides what "confirmed" means. With `NOT_DELIVERED`, a `DELIVERED` (or `READ`) satisfies it. With
`NOT_READ`, only `READ` does. The device never sees the policy or the timeout.

### Notification states

```mermaid
stateDiagram-v2
  [*] --> PENDING: registered, user has an active device
  [*] --> NO_DEVICE_REGISTERED: registered, no active device
  PENDING --> DELIVERED: DELIVERED event
  PENDING --> READ: READ event
  DELIVERED --> READ: READ event
  PENDING --> CANCELLED: sender DELETE
  DELIVERED --> CANCELLED: sender DELETE
  PENDING --> DISPATCHING: due, policy unmet
  DELIVERED --> DISPATCHING: due, policy NOT_READ unmet
  PENDING --> FALLBACK_EXPIRED: claimed after the trigger window, or circuit open
  DELIVERED --> FALLBACK_EXPIRED: claimed after the trigger window, or circuit open
  DISPATCHING --> DISPATCHING: retryable failure, backoff
  DISPATCHING --> FALLBACK_TRIGGERED: webhook 2xx
  DISPATCHING --> FALLBACK_FAILED: final 4xx or attempts exhausted
  DISPATCHING --> FALLBACK_EXPIRED: next retry after the window, or circuit open
  READ --> [*]
  CANCELLED --> [*]
  FALLBACK_TRIGGERED --> [*]
  FALLBACK_FAILED --> [*]
  FALLBACK_EXPIRED --> [*]
  NO_DEVICE_REGISTERED --> [*]
```

`DISPATCHING` is reported as `FALLBACK_PENDING` by the API. Events that arrive after a fallback was claimed are stored
as `LATE` and change nothing. Full rules: [architecture.md](architecture.md#notification-lifecycle).

## 4. Backend

One Spring Boot application (Java 21) in one container image. PostgreSQL is its only stateful dependency. The same
image runs as `api`, `worker` or both (Spring profiles). Replicas coordinate only through PostgreSQL row locks.

```mermaid
flowchart LR
  subgraph http["HTTP API (every instance)"]
    F["Filter chain<br/>trace id, access log, 64 KB body limit,<br/>sender key / device JWT, rate limit"]
    NC["notifications<br/>register, get, list, cancel"]
    DC["devices<br/>register, refresh, revoke"]
    EC["tracking<br/>DELIVERED / READ events"]
  end
  subgraph bg["Background (worker profile)"]
    CB["CircuitBreaker<br/>per-app check before the claim,<br/>suppresses open apps"]
    FS["FallbackScheduler<br/>claim due rows"]
    FD["FallbackDispatcher<br/>decision + trigger window"]
    WC["HttpWebhookClient<br/>HMAC signature"]
    RJ["RetentionJob"]
  end
  APPS["apps<br/>per-app configuration"]
  DB[("PostgreSQL<br/>notification, notification_event, device,<br/>fallback_attempt, daily_stats,<br/>fallback_circuit")]
  F --> NC & DC & EC
  NC & DC & EC --> DB
  FS -- "first: evaluate" --> CB --> DB
  FS -- "then: claim" --> DB
  FS --> FD --> WC
  RJ --> DB
  APPS -.-> NC & DC & FD
```

How the worker sends one fallback safely, even with several worker replicas:

```mermaid
sequenceDiagram
  participant W as Worker replica
  participant DB as PostgreSQL
  participant FR as Fallback receiver
  W->>DB: circuit check under advisory lock: due fallbacks vs. delivered share of their cohort, per app
  alt circuit OPEN (tracking missing) or fallbacks suspended
    W->>DB: close the app's due rows FALLBACK_EXPIRED, webhook not called
  end
  W->>DB: claim the other apps' rows due by the check's instant, FOR NO KEY UPDATE SKIP LOCKED, set lease_until
  Note over W,DB: other replicas skip locked rows, so no row is claimed twice
  W->>W: inside due_at + trigger window?
  alt inside the window
    W->>FR: POST, X-RP-Signature, Idempotency-Key = trackingId
    FR-->>W: 2xx / 4xx / 5xx / timeout
    W->>DB: write the outcome, fenced on lease_until
  else outside the window
    W->>DB: FALLBACK_EXPIRED, webhook not called
  end
  Note over W,DB: if the worker dies, the lease ends and another replica re-claims the row
```

The claim takes only rows that were due when the circuits were checked, so every fallback fired was counted by a check.
The circuit closes by itself once deliveries are reported again; an operator with `fallback-control` can force it from
the dashboard. Fallbacks suppressed while it was open are never re-sent. Opens, closes and failed checks are logged and
counted (`rp_fallback_circuit_transitions_total`, `rp_fallback_circuit_check_failed_total`).

Reference: [architecture.md](architecture.md), [api.md](api.md), [security.md](security.md),
[operations.md](operations.md) (circuit breaker and its runbook).

## 5. Ops dashboard

A page (`/admin/`) and API (`/v1/admin`) served by the backend, read-only except the fallback resume, for support ("what
happened to this push?"), ops (rates, backlog and fallback circuits now) and product (daily trends). It exists only when an admin key is configured; otherwise both paths
answer `404`. Guide: [admin.md](admin.md).

```mermaid
flowchart LR
  BR["Browser<br/>admin key in sessionStorage"]
  subgraph be["Backend (every instance)"]
    PG["/admin/ page<br/>static files, security headers"]
    AA["/v1/admin API<br/>X-Admin-Key, audit log;<br/>only write: resume fallbacks"]
  end
  subgraph wk["Backend (worker profile)"]
    WR["FallbackDispatcher<br/>one row per webhook call"]
    RU["DailyRollupJob<br/>nightly, advisory lock"]
    RJ["RetentionJob"]
    CB["CircuitBreaker<br/>per-app circuit, history"]
  end
  DB[("PostgreSQL<br/>notification, notification_event,<br/>fallback_attempt, daily_stats,<br/>fallback_circuit(_event)")]
  BR -- "loads page" --> PG
  BR -- "JSON" --> AA
  AA -- "live windows, search, detail" --> DB
  AA -- "older days" --> DB
  WR -- "fallback_attempt" --> DB
  RU -- "daily_stats" --> DB
  RJ -- "checks the rollup first" --> DB
  CB -- "fallback_circuit, events" --> DB
  AA -- "circuits, history, resume (fallback-control)" --> DB
```

Live windows and the last 7 days are computed from `notification`. Older days come from `daily_stats`, which outlives the
notifications. Retention skips its purge when the rollup did not run, so no day is deleted before it was counted.

## 6. Java sender client and payload jar

Two optional libraries for Java senders, independent of each other. The **payload jar** (Java 8+, no dependency, no
network) generates the tracking id and builds or decorates the APNs and FCM payloads, so the sender does not need to
know the payload rules; golden vectors define the same rules for other languages. The **sender client** (JDK 17+,
`java.net.http`) calls the sender API; any HTTP client can do the same ([api.md](api.md)).

```mermaid
flowchart LR
  APP["Your Java backend"] --> PJ["payload jar<br/>TrackingIds, ApnsPayloads, FcmPayloads"]
  PJ -- "tid + tracked payload" --> APP
  APP --> SC["SenderClient<br/>(immutable, thread-safe, timeout 1 s)"]
  SC -- "register {trackingId: tid, ...}<br/>get / list / cancel" --> API["reliable-push /v1/notifications"]
  APP -- "push with rp_tid = tid, always<br/>(your APNs / FCM client)" --> PP["APNs / FCM"]
```

A `PayloadException` means the push cannot be tracked: send it untracked, as before. Reference:
[payload-java/README.md](../payload-java/README.md), [client-java/README.md](../client-java/README.md).

## 7. Android SDK

Kotlin library, minSdk 26, Android platform APIs only. It plugs into the app's own `FirebaseMessagingService` and
ignores every message without `rp_tid`.

```mermaid
flowchart TB
  START["App start / foreground / login"] --> ER["ReliablePush.ensureRegistered(provider)<br/>returns at once"]
  ER -- "no or expired token: ID token → POST /v1/devices<br/>near expiry or renewalNeeded: token:refresh<br/>else: nothing" --> API["reliable-push"]
  API -- "device JWT" --> TS["TokenStore<br/>(encrypted with Android Keystore)"]
  FCM["FCM data-only message<br/>rp_tid, title, body (high priority)"] --> MS["App's FirebaseMessagingService"]
  MS --> H["ReliablePushMessaging.onMessageReceived"]
  H -- "no rp_tid: returns false" --> OWN["App's own handling"]
  H -- "tracked" --> B["Build the notification<br/>(your customizer)"]
  B --> G{"Gate on the final channel:<br/>enabled, permitted, channel open?"}
  G -- "no" --> SYS["Handed to the system, nothing recorded<br/>(false; the fallback decides)"]
  G -- "yes" --> Q["DELIVERED persisted on disk"]
  Q --> POST["Local notification posted"]
  POST --> S["SDK thread: one POST /v1/events<br/>no token renewal"]
  H -. "any SDK error" .-> MIN["Minimal notification posted"]
  TAP["User taps"] --> TO["ReliablePush.trackOpen(intent)"] --> Q2["READ queued"] --> S
  S -- "offline / 5xx / 429" --> J["JobScheduler retry"] --> S
  S -- "401" --> RN["renewalNeeded stored,<br/>events kept for ensureRegistered"]
```

The SDK posts tracked notifications itself (data message → local notification, the market-standard model) and records
`DELIVERED` only when the notification will be shown. `DELIVERED` is on disk before the post: if the post itself then
fails, no fallback fires for that push (a missed fallback rather than a duplicate one). Reference: [android.md](android.md).

## 8. iOS SDK

Swift package, iOS 15+, Apple frameworks only. The app and its Notification Service Extension (NSE) share the device
token through a Keychain access group and the event queue through an App Group.

```mermaid
flowchart TB
  subgraph app["App process"]
    CFG["ReliablePush.configure(baseURL, appId, appGroup)"]
    REG["ensureRegistered(provider)<br/>at start, foreground, login;<br/>the only token renewal"]
    TO["trackOpen(response): READ"]
  end
  subgraph nse["Notification Service Extension"]
    DR["ReliablePushExtension.didReceive"]
  end
  subgraph shared["Shared storage"]
    KC["Keychain group<br/>device token"]
    AG["App Group<br/>event queue + state"]
  end
  APNS["APNs alert, mutable-content: 1, rp_tid"] --> DR
  DR -- "no rp_tid: returns false,<br/>nothing read" --> OWNI["Your extension's own handling"]
  REG -- "POST /v1/devices or token:refresh,<br/>only when needed" --> API["reliable-push"]
  REG --> KC
  DR -- "1. persist DELIVERED" --> AG
  DR -- "2. one send, no renewal<br/>(401: renewalNeeded)" --> API
  DR -- "3. contentHandler exactly once:<br/>send done or 2 s, at once on an SDK error" --> SHOW["System shows the notification"]
  TO --> AG
  AG -- "flush, one at a time<br/>(waits for the other process)" --> API
  KC -.-> DR
```

The extension calls the content handler exactly once: after the send, at the send timeout (`extensionSendTimeout`,
2 s by default; the settings read counts against it), or when iOS ends the extension, and at once when there is
nothing to send or an SDK step fails. It never renews the token: a `401` sets `renewalNeeded` in the App Group and the
app's next `ensureRegistered` acts on it. Reference: [ios.md](ios.md).

## 9. Harness and web console

The harness is a local test stack. It is never built into the backend image and never deployed. It runs the real
backend (one API, two workers) with simulated senders, devices and fallback receivers.

```mermaid
flowchart LR
  subgraph compose["docker compose (local only)"]
    PG[("postgres")]
    API["api :8080"]
    WA["worker-a"]
    WB["worker-b"]
    subgraph H["harness :8090"]
      CON["Web console<br/>6 screens"]
      FS["Fake sender"]
      DS["Device simulator"]
      RCV["Fallback receiver<br/>(signature check)"]
      SR["Scenario runner"]
      DP["Device proxy :8091<br/>online / offline switch<br/>loopback unless configured"]
      RS["Optional real FCM / APNs senders"]
    end
  end
  USER["You (browser)"] --> CON
  FS & DS --> API
  API & WA & WB --> PG
  WA & WB --> RCV
  EMU["Android emulator<br/>sample app"] --> API
  SIM["iOS Simulator<br/>sample app"] --> DP --> API
  SCRIPTS["scripts: demo.sh, e2e.sh,<br/>android-e2e.sh, ios-e2e.sh"] --> compose
```

Reference: [testing.md](testing.md), [getting-started.md](getting-started.md).

## 10. Helm chart and deployment

The chart installs the backend only. PostgreSQL is yours (managed or operator). It runs **combined** (every pod is
API and worker) or **split** (API pods behind the Service, worker pods with no traffic).

```mermaid
flowchart LR
  subgraph combined["Combined mode"]
    I1["Ingress / Route"] --> S1["Service"] --> P1["Pods: api + worker"]
  end
  subgraph split["Split mode"]
    I2["Ingress / Route"] --> S2["Service"] --> P2["API pods"]
    P3["Worker pods<br/>(no Service traffic)"]
  end
  P1 --> PG1[("PostgreSQL")]
  P2 --> PG2[("PostgreSQL")]
  P3 --> PG2
  P1 -. webhook .-> R1["Fallback receiver"]
  P3 -. webhook .-> R2["Fallback receiver"]
```

Values for GKE and OpenShift are included (non-root, arbitrary UID). Reference: [deployment.md](deployment.md).

## 11. Load tests

```mermaid
flowchart LR
  G["Gatling container<br/>(same compose network)"] -- "500 notifications/s<br/>1,000 events/s" --> API["api<br/>2 CPUs / 1 GiB"]
  API --> PG[("postgres")]
  W["workers"] --> PG
  W --> RCV["harness receiver"]
  P["BurstDrainProbe"] -- "/actuator/prometheus" --> W
  G --> OUT["harness/build/load/summary.json"]
```

NFR-2 (at least 1,000 events/s per replica) was validated locally. Reference:
[operations.md, "Measured capacity"](operations.md#measured-capacity) and [testing.md](testing.md#load-tests-local-only).
