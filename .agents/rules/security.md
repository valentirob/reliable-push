---
trigger: model_decision
description: Apply when touching authentication, device/sender tokens, API endpoints, webhooks, rate limiting, secrets or personal data.
---

# Security rules

- Sender API: per-app API key, stored hashed; constant-time comparison.
- Device registration: validate the app's OIDC ID token against the configured issuer JWKS (issuer, audience, expiry, signature). Never trust a `userId` sent by the client.
- Device JWT: ES256, signed by the backend; carries `appId`, `userId`, `deviceId`, `exp`. Clients validate expiry locally and refresh only near expiry.
- Events: accept a `trackingId` only if it belongs to the same `appId` + `userId` as the device JWT. Duplicates are idempotent no-ops.
- Rate limit per device and per app; return 429 with `Retry-After`.
- Fallback webhooks: HMAC-SHA256 signature header + timestamp, `Idempotency-Key` = `trackingId`, bounded retries with backoff.
- Data minimisation: SDKs send only trackingId, event type, timestamp, device/app ids. No message content from devices.
- No secrets, keys or real tokens in code, tests or docs; use generated test values.
- Validate all input (Bean Validation), bound payload sizes, never log tokens or message bodies.
