# reliable-push

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

reliable-push is an MIT-licensed add-on for teams that already send push
notifications through FCM or APNs. It tracks whether each push was **delivered**
and **read** on the device and, if a per-notification policy is not satisfied
within N minutes, triggers a **fallback** by calling a configurable, HMAC-signed
webhook (`userId`, message, `trackingId`) that routes to SMS, email or any other
channel. It runs as a sidecar: your existing backend keeps sending pushes itself
and keeps the FCM/APNs credentials.

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
| `android/` | Android SDK (FCM v1 data mode + opt-in AndroidConfigV2 mode) | Kotlin, minSdk 26 |
| `ios/` | iOS SDK + Notification Service Extension helper | Swift, iOS 15+, SPM |
| `client-java/` | Optional zero-dependency Java client for the sender API | Java 17+, JDK only |
| `harness/` | Fake sender, fake fallback receiver, device simulator, E2E scenarios, web console for manual tests and demos | Java, plain HTML/JS, docker-compose |
| `docs/` | Requirements, specs, plans, guides, status | Markdown |

Components land incrementally; see [docs/STATUS.md](docs/STATUS.md) for what
exists today.

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
docker run -d --name rp-app --network rp-net -p 8080:8080 \
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
`./mvnw -B verify -Pimage` (image tests), see [docs/testing.md](docs/testing.md). All configuration keys, the log format and
the metrics are in [docs/operations.md](docs/operations.md).

## Documentation

- [Requirements](docs/requirements.md)
- [Design spec](docs/specs/2026-11-03-reliable-push-design.md)
- [Architecture](docs/architecture.md)
- [API reference](docs/api.md)
- [Integration guide](docs/integration-guide.md)
- [Security](docs/security.md)
- [Operations](docs/operations.md)
- [Testing](docs/testing.md)
- [Project status](docs/STATUS.md)
- [AGENTS.md](AGENTS.md) (context for AI coding agents)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [License](LICENSE)
