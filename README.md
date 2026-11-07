# reliable-push

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

reliable-push is an MIT-licensed add-on for teams that already send push
notifications through FCM or APNs. It tracks whether each push was **delivered**
and **read** on the device and, if a per-notification policy is not satisfied
within N minutes, triggers a **fallback** by calling a configurable, HMAC-signed
webhook (`userId`, message, `trackingId`) that routes to SMS, email or any other
channel. It runs as a sidecar: your existing backend keeps sending pushes itself
and keeps the FCM/APNs credentials.

![reliable-push at a glance: your backend, FCM/APNs, an iPhone and an Android phone with the tracking module, the reliable-push backend, the fallback clock and your SMS service](docs/images/reliable-push-at-a-glance.png)

The backend also serves a read-only **ops dashboard** at `/admin/` (delivery and read rates, the due backlog, daily trends,
search, and a timeline per notification), off until you configure an admin key: [docs/admin.md](docs/admin.md).

## A sidecar, not a push gateway

reliable-push sits **next to** your push path, not on it. The backend and the mobile SDKs only observe and react, so
they never change how your notifications are sent or shown:

- **Your backend keeps sending the pushes** with its own FCM / APNs credentials. reliable-push never sends a push and
  never sees those credentials.
- **If the reliable-push backend is down, your pushes still go out.** Only tracking and fallbacks pause. Device events
  wait in the SDK's queue and are sent when the backend is back.
- **The SDKs never hide or block a notification.** Pushes without `rp_tid` are left to your own code. Network errors
  stay inside the SDK and never reach the app.

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
  BE -. "① register, get trackingId" .-> RP
  DEV -. "② SDK reports DELIVERED / READ" .-> RP
```

What happens when each part fails, the component diagrams and the full lifecycle: [docs/overview.md](docs/overview.md).
To present the project, open [docs/presentation.html](docs/presentation.html) in a browser.

## How it works

1. Your backend registers the notification with `POST /v1/notifications`
   (`userId`, message, policy) using an `X-Api-Key` and receives a `trackingId`.
2. Your backend sends the push through FCM/APNs itself, adding the `trackingId`
   (`rp_tid`) to the payload.
3. The device SDK (iOS Notification Service Extension / Android FCM service)
   reports `DELIVERED` to `POST /v1/events` with its device JWT.
4. When the user taps the notification, the SDK reports `READ`.
5. The reliable-push worker periodically claims notifications that are due and
   whose policy is still unmet, and calls your fallback webhook with a signed
   request (`trackingId`, `userId`, message, `reason`).

## Components

| Path | What | Stack |
|---|---|---|
| `backend/` | Tracking + fallback service (modular monolith, `api`/`worker` profiles) | Java 21, Spring Boot 4, PostgreSQL, Flyway |
| `android/` | Android SDK, FCM v1 data mode ([guide](docs/android.md); the AndroidConfigV2 mode waits for Google's GA) | Kotlin, minSdk 26 |
| `ios/` (+ root `Package.swift`) | iOS SDK + Notification Service Extension helper ([guide](docs/ios.md)) | Swift, iOS 15+, SPM |
| `client-java/` | Optional zero-dependency Java client for the sender API | Java 17+, JDK only |
| `harness/` | Fake sender, fake fallback receiver, device simulator, E2E scenarios, web console for manual tests and demos | Java, plain HTML/JS, docker-compose |
| `deploy/helm/` | Helm chart for the backend (combined or split API/worker, GKE and OpenShift values) | Helm, Kubernetes ≥ 1.30 |
| `loadtest/` | Local load tests (NFR-2 ingest, fallback burst) run by `./scripts/load.sh` | Java 21, Gatling, docker-compose |
| `docs/` | Requirements, specs, plans, guides, status | Markdown |

All components are released; the latest release is `v0.1.2` ([CHANGELOG.md](CHANGELOG.md)). What is left is in
[docs/TODO.md](docs/TODO.md), and the session log is in [docs/STATUS.md](docs/STATUS.md). To build, run and test each
component step by step, see [docs/getting-started.md](docs/getting-started.md).

### Try it: demo console

`./scripts/demo.sh` starts the whole stack locally (Docker only) and opens the harness web console at
<http://127.0.0.1:8090/>: create simulated devices, send pushes, watch delivery tracking and fallbacks live. Details in
[docs/testing.md](docs/testing.md#web-console-and-demo). The end-to-end suite is `./scripts/e2e.sh` (local only, never in CI).
Java sender client: [client-java/README.md](client-java/README.md).

## Quick start

Run the backend locally with Docker (no JDK needed for this path) and register your first notification. The values below
are placeholders generated on the spot with `openssl`; use your own secret management for anything real.

```sh
# 1. Build the image (a jlink-trimmed JRE image, about 105 MB)
docker build -t reliable-push-backend:dev backend

# 2. Secrets: sender API key (+ its SHA-256), webhook HMAC secret, database password, device-token signing key pair
#    (run steps 2-3 in a scratch directory: it receives k1.pem, k1.pub.pem and rp.yml, which must not be committed)
export RP_SENDER_KEY=$(openssl rand -hex 32)
export RP_SENDER_KEY_SHA256=$(printf '%s' "$RP_SENDER_KEY" | openssl dgst -sha256 -hex | sed 's/^.* //')
export RP_SHOP_HOOK_SECRET=$(openssl rand -hex 32)
export RP_DB_PASSWORD=$(openssl rand -hex 16)
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve -out k1.pem
openssl pkey -in k1.pem -pubout -out k1.pub.pem

# 3. App configuration (placeholders: set your own identity provider and fallback receiver)
cat > rp.yml <<EOF
reliable-push:
  device-token:
    signing-keys:
      - kid: k1
        private-key-pem-ref: env:RP_DEVICE_KEY_K1
        public-key-pem-ref: env:RP_DEVICE_PUB_K1
    current-kid: k1
  apps:
    shop:
      sender-api-key-sha256: "${RP_SENDER_KEY_SHA256}"
      identity:
        type: oidc
        issuer: https://idp.example.com        # placeholder: your OIDC issuer
        audience: shop-mobile
      fallback:
        url: https://fallback.example.com/hook # placeholder: your fallback receiver
        hmac-secret-ref: env:RP_SHOP_HOOK_SECRET
EOF

# 4. PostgreSQL and the backend (Flyway creates the schema at startup)
docker network create rp-net
docker run -d --name rp-db --network rp-net -e POSTGRES_DB=reliablepush -e POSTGRES_USER=rp \
  -e POSTGRES_PASSWORD="$RP_DB_PASSWORD" postgres:16
until docker exec rp-db pg_isready -U rp -d reliablepush >/dev/null 2>&1; do sleep 1; done   # wait for PostgreSQL (--restart covers its init restart)
docker run -d --name rp-app --network rp-net --restart on-failure -p 8080:8080 \
  -v "$PWD/rp.yml:/config/application.yml:ro" -e SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/config/ \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://rp-db:5432/reliablepush \
  -e SPRING_DATASOURCE_USERNAME=rp -e SPRING_DATASOURCE_PASSWORD="$RP_DB_PASSWORD" \
  -e RP_SHOP_HOOK_SECRET -e RP_DEVICE_KEY_K1="$(cat k1.pem)" -e RP_DEVICE_PUB_K1="$(cat k1.pub.pem)" \
  reliable-push-backend:dev

# 5. Check health, then register a notification (fallback unless it is read within 10 minutes)
curl -s http://localhost:8080/actuator/health/readiness
curl -s -X POST http://localhost:8080/v1/notifications \
  -H "X-Api-Key: $RP_SENDER_KEY" -H 'Content-Type: application/json' \
  -d '{"userId":"user-42","policy":"NOT_READ","timeoutMinutes":10,"fallback":{"sms":"Your order has shipped."}}'
# => 201 {"trackingId":"...","state":"PENDING","dueAt":"...", ...}
```

Put the returned `trackingId` into your push payload as `rp_tid`, and implement the fallback receiver that verifies the
`X-RP-Signature`: see the [integration guide](docs/integration-guide.md). The worker calls the receiver when the policy is
still unmet at `dueAt` (with the placeholder URL above the call fails and is retried, which you can watch in the JSON logs
with `docker logs rp-app`). Cleanup: `docker rm -f rp-app rp-db && docker network rm rp-net`.

Building from source needs JDK 21 and Docker: `cd backend && ./mvnw -B verify` (tests) and
`./mvnw -B verify -Pimage` (image tests); `client-java` and `harness` build from the repo root with `./mvnw -B -pl client-java verify` and `./mvnw -B -pl harness -am verify`. See [docs/testing.md](docs/testing.md). All configuration keys, the log format and
the metrics are in [docs/operations.md](docs/operations.md).

## Documentation

- [System overview with diagrams](docs/overview.md)
- [Presentation (HTML slides)](docs/presentation.html)
- [Getting started: run and test each component](docs/getting-started.md)
- [Requirements](docs/requirements.md)
- [Design spec](docs/specs/2026-11-03-reliable-push-design.md)
- [Architecture](docs/architecture.md)
- [API reference](docs/api.md)
- [Integration guide](docs/integration-guide.md)
- [Ops dashboard](docs/admin.md)
- [Security](docs/security.md)
- [Operations](docs/operations.md)
- [Deployment (Kubernetes, GKE, OpenShift)](docs/deployment.md)
- [Testing](docs/testing.md)
- [Project status](docs/STATUS.md)
- [Remaining work (TODO)](docs/TODO.md)
- [Changelog](CHANGELOG.md)
- [AGENTS.md](AGENTS.md) (context for AI coding agents)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [License](LICENSE)
