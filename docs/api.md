# API reference

This page mirrors the OpenAPI 3 contract in [`backend/src/main/resources/openapi.yaml`](../backend/src/main/resources/openapi.yaml);
the contract is authoritative. Contract tests validate the real responses against it.

- Base URL: wherever the backend is exposed (examples use `http://localhost:8080`). TLS is terminated at the ingress.
- Bodies are JSON (`Content-Type: application/json`), at most **64 KB**; multipart is not supported. Unknown JSON fields in
  request bodies are ignored.
- Timestamps are ISO-8601 UTC (`2026-11-03T10:15:30.123456Z`). Fields that have no value are `null`.
- Every response carries `X-Request-Id`, the correlation id that appears as `trace.id` in the logs. Send your own
  `traceparent` or `X-Request-Id` (`[A-Za-z0-9._-]`, at most 64 characters) to correlate.

## Authentication

| API | Credential | Header |
|---|---|---|
| Sender API (`/v1/notifications/**`) | Per-app API key; the backend stores only its SHA-256 | `X-Api-Key: <key>` |
| Device API (`/v1/devices/token:refresh`, `/v1/devices/me`, `/v1/events`) | Device token (ES256 JWT) from `POST /v1/devices` | `Authorization: Bearer <deviceToken>` |
| `POST /v1/devices` | The app's identity credential (OIDC ID token or signed grant), in the body | none |

The `appId` is derived from the API key or the token, never from a body. A missing and a wrong credential get the same
`401`, so responses do not reveal whether a credential exists.

## Sender API

### `POST /v1/notifications` — register a notification

Register before you send the push, put the returned `trackingId` into the push payload as `rp_tid`
([integration guide](integration-guide.md)).

| Field | Type | Notes |
|---|---|---|
| `userId` | string, 1-256 | Required. The recipient, as the app's identity provider names the user (the `sub` or configured claim) |
| `externalId` | string, 1-128 | Your own id. Unique per app; makes the call idempotent |
| `policy` | `NOT_DELIVERED` \| `NOT_READ` | Default: the app's `default-policy` |
| `timeoutMinutes` | integer 1-10080 | Default: the app's `default-timeout-minutes` |
| `fallback` | JSON object | Required. Sent verbatim to the fallback webhook (for example the SMS text). At most 4096 bytes serialized; never returned by the API, never logged |

```sh
curl -sS -X POST http://localhost:8080/v1/notifications \
  -H "X-Api-Key: $RP_SENDER_KEY" -H 'Content-Type: application/json' \
  -d '{"userId":"user-42","externalId":"order-1001-shipped","policy":"NOT_READ","timeoutMinutes":10,
       "fallback":{"sms":"Your order 1001 has shipped."}}'
```

`201 Created`, header `Location: /v1/notifications/{trackingId}`:

```json
{"trackingId":"6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11","userId":"user-42","externalId":"order-1001-shipped",
 "state":"PENDING","policy":"NOT_READ","timeoutMinutes":10,"createdAt":"2026-11-03T10:00:00Z",
 "dueAt":"2026-11-03T10:10:00Z","deliveredAt":null,"readAt":null,"closedAt":null}
```

With an `externalId` that already exists for the app nothing is created and the existing record is returned (same
`trackingId`, still `201`), also when two such requests race.

### `GET /v1/notifications/{trackingId}` — get one

`200` with the object above. A notification of another app is `404`, exactly like an unknown or malformed id.

```sh
curl -sS http://localhost:8080/v1/notifications/6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11 -H "X-Api-Key: $RP_SENDER_KEY"
```

`state` is one of `PENDING`, `DELIVERED`, `READ`, `FALLBACK_PENDING` (fallback claimed, webhook in progress or awaiting a
retry), `FALLBACK_TRIGGERED`, `FALLBACK_FAILED`, `FALLBACK_EXPIRED`, `CANCELLED`. `dueAt` is `null` once the policy is
satisfied or the notification is closed.

### `GET /v1/notifications` — list

Newest first (`createdAt` descending, ties by `trackingId` descending), scoped to the API key's app.

| Query | Notes |
|---|---|
| `userId` | 1-256 characters |
| `state` | A state name; `FALLBACK_PENDING` for a fallback in progress |
| `since` | ISO-8601 date-time with offset; inclusive; years 1970-9999 |
| `limit` | 1-100, default 20 |
| `cursor` | The `nextCursor` of the previous page; opaque, only valid with the same filters |

```sh
curl -sS 'http://localhost:8080/v1/notifications?userId=user-42&state=FALLBACK_TRIGGERED&limit=20' -H "X-Api-Key: $RP_SENDER_KEY"
```

```json
{"items":[{"trackingId":"...","state":"FALLBACK_TRIGGERED","...":"..."}],"nextCursor":"opaque-or-null"}
```

### `DELETE /v1/notifications/{trackingId}` — cancel

Only open `PENDING` and `DELIVERED` notifications can be cancelled: they become `CANCELLED` and no fallback is sent. `204`;
any other state (including an already cancelled one) is `409`, and so is a `DELIVERED` notification whose policy
`NOT_DELIVERED` is already satisfied (it is closed: `closedAt` set); unknown id or other app `404`.

```sh
curl -sS -X DELETE http://localhost:8080/v1/notifications/6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11 -H "X-Api-Key: $RP_SENDER_KEY"
```

## Device API (used by the mobile SDKs)

### `POST /v1/devices` — register a device

| Field | Notes |
|---|---|
| `appId` | Required, at most 64 characters |
| `idToken` | Required. The OIDC ID token (or signed grant, depending on the app's `identity.type`); never logged |
| `platform` | `IOS` or `ANDROID` |
| `deviceId` | Optional UUID issued earlier, when the same installation registers again |

`201`: `{"deviceId":"<uuid>","deviceToken":"<jwt>","expiresAt":"..."}`. The user id is taken from the verified credential,
never from the body. Re-registering an existing `deviceId` of the same app and user issues a new token and invalidates the
older ones; a `deviceId` of another user is not reused (a new id is issued). Every rejected credential gets the same `401`
body. `404` unknown `appId`. `503` with `Retry-After: 30` when the identity provider's keys cannot be fetched.
Registration is limited per user (the app's `device-per-minute`), counted after the credential was verified.

```sh
curl -sS -X POST http://localhost:8080/v1/devices -H 'Content-Type: application/json' \
  -d '{"appId":"shop","idToken":"'"$ID_TOKEN"'","platform":"ANDROID"}'
```

### `POST /v1/devices/token:refresh` — refresh the device token

Device token required, no body. `200`: `{"deviceToken":"<jwt>","expiresAt":"..."}`. The old token stays valid until it
expires.

### `DELETE /v1/devices/me` — revoke the device

Device token required. `204`; afterwards every token of the device gets `401`.

### `POST /v1/events` — report delivery and read events

Device token required. Body `{"events":[{"trackingId":"<uuid>","type":"DELIVERED|READ","occurredAt":"..."}]}` with 1 to 50
events.

```sh
curl -sS -X POST http://localhost:8080/v1/events -H "Authorization: Bearer $DEVICE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"events":[{"trackingId":"6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11","type":"DELIVERED","occurredAt":"2026-11-03T10:00:04Z"}]}'
```

`200` even if individual events are not applied; the outcome is reported per item, in request order:

```json
{"accepted":1,"ignored":0,"items":[{"trackingId":"6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11","outcome":"ACCEPTED"}]}
```

| Outcome | Meaning | Counted in |
|---|---|---|
| `ACCEPTED` | Stored and the state moved | `accepted` |
| `LATE` | Stored, but the transition no longer applied (fallback already claimed, cancelled, already read) | `accepted` |
| `DUPLICATE` | Same `trackingId` and type was seen before | `ignored` |
| `NOT_FOUND` | Unknown `trackingId`, or one of another app | `ignored` |
| `FORBIDDEN` | `trackingId` of another user of the same app | `ignored` |

`occurredAt` is clamped by the server to `[received - 7 days, received]`. Retrying a whole batch is safe: processed items come
back as `DUPLICATE`.

## Errors

Errors are RFC 9457 `application/problem+json` documents; rejected values are never echoed.

```json
{"title":"Validation Failed","status":422,"detail":"The request is not valid.","instance":"/v1/notifications",
 "errors":[{"field":"fallback","message":"is required and must be a JSON object of at most 4096 bytes"}]}
```

| Status | When |
|---|---|
| `400` | Body is not readable JSON (malformed, wrong types, unknown enum value) |
| `401` | Missing or invalid credential (`WWW-Authenticate: ApiKey` or `Bearer`) |
| `404` | Unknown id (or an id of another app); unknown `appId` on device registration |
| `409` | The notification is not in a state that allows the operation (cancel) |
| `413` | Body larger than 64 KB |
| `415` | Unsupported media type (for example multipart) |
| `422` | A field is invalid; `errors` names each field |
| `429` | Rate limit exceeded; `Retry-After` in seconds |
| `503` | Temporarily overloaded: no database connection within 5 s (any endpoint, `Retry-After: 5`), or the identity provider key set is unavailable on `POST /v1/devices` (`Retry-After: 30`). Retry after the given seconds |
| `500` | Unexpected error; generic body, details only in the server log |

Rate limits are token buckets per instance: per sender app (default 6,000 requests per minute) and per device (default 60
per minute); see [operations.md](operations.md) for how they scale with replicas.

## Operational endpoints

Not part of the OpenAPI contract: `GET /actuator/health/liveness`, `GET /actuator/health/readiness` and
`GET /actuator/prometheus`. They should be reachable from the cluster only; see [operations.md](operations.md).
