# Integration guide

reliable-push is a sidecar: your existing backend keeps sending pushes through FCM and APNs and keeps those credentials.
You add two things: (1) register each notification with reliable-push and put its `trackingId` into the push, and (2) run
a **fallback receiver**, an HTTPS endpoint that reliable-push calls when a push was not delivered or read in time. Mobile
apps embed the SDK (Android and iOS, later cycles), which reports `DELIVERED` and `READ`.

```
your backend ── 1. POST /v1/notifications ──► reliable-push ◄── 4. DELIVERED / READ ── device SDK
     │ ◄── trackingId                                │
     └─ 2. send push with rp_tid = trackingId ──► FCM / APNs ──► device
                                                      │ 5. due and policy unmet
your fallback receiver ◄── 6. signed webhook ─────────┘      (SMS, email, ...)
```

## Prerequisites (operator side)

Per app, in the backend configuration ([operations.md](operations.md)): a sender API key (only its SHA-256 is configured),
the identity setup that lets devices register (`oidc` or `signed-grant`), and the `fallback` block with your receiver URL
and HMAC secret. Defaults for `policy` and `timeoutMinutes` are per app as well.

## 1. Register the notification

Call `POST /v1/notifications` **before** sending the push, with the recipient `userId` (the same user id your identity
provider puts in the ID token, `sub` unless configured otherwise), the fallback content, and optionally the policy and
timeout:

```sh
curl -sS -X POST "$RP_URL/v1/notifications" \
  -H "X-Api-Key: $RP_SENDER_KEY" -H 'Content-Type: application/json' \
  -d '{"userId":"user-42","externalId":"order-1001-shipped","policy":"NOT_DELIVERED","timeoutMinutes":10,
       "fallback":{"channel":"sms","to":"+15550100","text":"Your order 1001 has shipped."}}'
# 201 {"trackingId":"6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11","state":"PENDING","dueAt":"...", ...}
```

- `policy`: `NOT_DELIVERED` (fallback unless the push reached the device) or `NOT_READ` (fallback unless the user opened it).
- `fallback` is an opaque JSON object of at most 4 KB. reliable-push stores it and hands it back to your receiver unchanged;
  it never appears in API responses or logs.
- Send an `externalId` (your own message id) and retries become safe: the same `externalId` returns the same `trackingId`.
- Failure handling is yours: if registration fails, decide whether to send the push anyway (it will then be untracked) or
  to retry. A push without `rp_tid` is not tracked.

Full reference: [api.md](api.md).

### From Java: the sender client

Java and Kotlin backends can use the zero-dependency client instead of raw HTTP (JDK 17+, `java.net.http`, no runtime
dependencies). Install, error handling and thread-safety notes are in [`client-java/README.md`](../client-java/README.md):

```java
SenderClient client = SenderClient.builder()
        .baseUrl(URI.create(System.getenv("RP_URL")))
        .apiKey(System.getenv("RP_SENDER_KEY"))
        .build();

Notification n = client.register(RegisterRequest.builder()
        .userId("user-42")
        .externalId("order-1001-shipped")
        .policy(FallbackPolicy.NOT_DELIVERED)
        .timeoutMinutes(10)
        .fallback(Map.of("channel", "sms", "to", "+15550100", "text", "Your order 1001 has shipped."))
        .build());
String rpTid = n.trackingId();   // put it into the push as rp_tid
```

`register`, `get`, `list` and `cancel` map to the endpoints above. The timeout covers the whole exchange, failures are
`ReliablePushException` (HTTP status or `-1`, parsed problem details, `Retry-After` on 429 and 503).

## 2. Embed `rp_tid` in the push

The tracking id travels as the string `rp_tid` (a UUID). The policy and timeout are **not** sent to the device.

### iOS (APNs)

The Notification Service Extension needs a visible alert and `mutable-content`; `rp_tid` sits at the top level of the payload,
next to `aps`:

```json
{
  "aps": {
    "alert": { "title": "Order shipped", "body": "Your order 1001 is on its way." },
    "mutable-content": 1
  },
  "rp_tid": "6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11"
}
```

### Android, mode v1 (default): FCM v1 data-only message

A data-only message with high priority. The SDK reports `DELIVERED` and **always** posts the visible notification itself,
so put title and body in `data` (strings only):

```json
{
  "message": {
    "token": "<device FCM token>",
    "data": {
      "rp_tid": "6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11",
      "title": "Order shipped",
      "body": "Your order 1001 is on its way."
    },
    "android": { "priority": "HIGH" }
  }
}
```

### Android, mode v2 (opt-in): `AndroidConfigV2` with `mutable_content`

A `RemoteNotification` with `mutable_content: true` and `rp_tid` in its data; the SDK implements `onNotificationReceived`
and must return a `NotificationResponse` within 20 seconds. It needs firebase-messaging 26.1.0-eap01 or later; older devices
fall back to the v1 `onMessageReceived` path. The exact message shape follows the FCM documentation for
`AndroidConfigV2` / `RemoteNotification`; the contract on our side is only that `rp_tid` is present in the notification's
data and `mutable_content` is `true`.

Pushes sent in another shape are not tracked, so they end in fallback by design ("unknown" means fallback).

## 3. Receive fallbacks

When the policy is unmet at `dueAt`, the worker sends a signed `POST` to the `fallback.url` configured for your app.

Headers:

| Header | Value |
|---|---|
| `Content-Type` | `application/json` |
| `X-RP-Timestamp` | Unix time in seconds when the attempt was signed |
| `X-RP-Signature` | `v1=` + lowercase hex of `HMAC-SHA256(secret, timestamp + "." + body)` |
| `Idempotency-Key` | The `trackingId` |
| `User-Agent` | `reliable-push/<version>` |

Body:

```json
{
  "trackingId": "6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11",
  "userId": "user-42",
  "fallback": { "channel": "sms", "to": "+15550100", "text": "Your order 1001 has shipped." },
  "reason": "NOT_DELIVERED",
  "attempt": 1
}
```

`fallback` is exactly the object you registered, `reason` is the unmet policy, `attempt` counts from 1.

### What the receiver must do

1. **Verify the signature** over the raw body bytes (not a re-serialized JSON), with the shared secret and a constant-time
   comparison. Reject a request whose `X-RP-Timestamp` is stale: more than 5 minutes from your clock is a sensible bound
   (attempts are signed when they are sent; retries get a fresh timestamp).
2. **De-duplicate on `Idempotency-Key`.** A call can arrive more than once: after a retry whose response was lost, or when
   a worker crashed between a successful call and recording it (this extra re-send is not counted against `max-attempts`).
   The `Idempotency-Key` (the `trackingId`) is the **same on every attempt** of one notification, so record it as handled only
   after the work is done or durably enqueued (for example insert it with a unique constraint in the same transaction that
   enqueues the SMS). If you record it first and then fail with a `5xx`, the retry is acknowledged as a duplicate and the SMS
   is never sent. Answer a key that is already recorded with `2xx` without sending again.
3. **Return a `2xx` quickly** (the default budget is 10 s for the whole exchange, `fallback.http-timeout`). Do slow work, such
   as calling an SMS provider, after acknowledging, or from a queue.

How reliable-push reads your response:

| Response | Result |
|---|---|
| `2xx` | Success: the notification becomes `FALLBACK_TRIGGERED` |
| `4xx` except `408` and `429` | Final failure: `FALLBACK_FAILED`, no retry. Use this for "I will never accept this" |
| `408`, `429`, `5xx`, redirects (`3xx`), timeout, connection error | Retried with backoff (default 1, 2, 4, 8 min, at most 5 attempts), as long as the retry still falls inside the trigger window; otherwise `FALLBACK_EXPIRED` |

Redirects are never followed. A fallback is only sent while `now <= dueAt + trigger window` (default 20 min), so a very late
call means the original moment has passed and the notification is closed as `FALLBACK_EXPIRED` without calling you.

### Java verification example (JDK only)

```java
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies the X-RP-Signature of a reliable-push fallback webhook. JDK only. */
public final class WebhookVerifier {

    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final byte[] secret;

    public WebhookVerifier(String hmacSecret) {
        this.secret = hmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param timestampHeader value of X-RP-Timestamp (epoch seconds)
     * @param signatureHeader value of X-RP-Signature ("v1=" + hex)
     * @param rawBody         the request body bytes exactly as received (before any JSON parsing)
     */
    public boolean isValid(String timestampHeader, String signatureHeader, byte[] rawBody, Instant now) {
        if (timestampHeader == null || signatureHeader == null || !signatureHeader.startsWith("v1=")) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return false;
        }
        // reject stale (replayed) and far-future timestamps
        Duration age = Duration.between(Instant.ofEpochSecond(timestamp), now).abs();
        if (age.compareTo(MAX_AGE) > 0) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestampHeader + ".").getBytes(StandardCharsets.US_ASCII));
            String expected = "v1=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
            // constant-time comparison
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII), signatureHeader.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

Use it before parsing the body, with the bytes as received:

```java
byte[] body = request.getInputStream().readAllBytes();
boolean ok = verifier.isValid(request.getHeader("X-RP-Timestamp"), request.getHeader("X-RP-Signature"), body, Instant.now());
if (!ok) { response.setStatus(401); return; }
// then: de-duplicate on request.getHeader("Idempotency-Key"), parse the JSON, enqueue the SMS, answer 200
```

A shell check for debugging (the secret and body are yours):

```sh
printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$RP_SHOP_HOOK_SECRET" -hex   # compare with the value after "v1="
```

## 4. Mobile apps

The SDKs (planned in later cycles) are initialised with the backend URL and `appId`, register the device with the user's ID
token after login (`POST /v1/devices`), report `DELIVERED` from the push entry point and `READ` on tap. Until the SDKs
exist, the device API in [api.md](api.md) is the contract.

## Operating notes

- Keep `fallback.url` on HTTPS in production; the secret is configured as an environment variable reference
  ([operations.md](operations.md)). Rotating the secret means changing it on both sides; accept both during the switch if
  you need zero failed calls.
- To stop a pending fallback (for example the order was cancelled), `DELETE /v1/notifications/{trackingId}`.
- Observe outcomes with `GET /v1/notifications/{trackingId}` or the list endpoint, or by metrics and logs
  ([operations.md](operations.md)).
