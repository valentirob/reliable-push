# reliable-push client (Java)

Zero-dependency (JDK 17+, `java.net.http`) client for the reliable-push **sender API**: register, get, list and
cancel notifications. Instances are immutable and thread-safe: create one `SenderClient` and share it.

## Install

Maven coordinates (not yet published to a repository):

```xml
<dependency>
  <groupId>io.reliablepush</groupId>
  <artifactId>reliable-push-client</artifactId>
  <version>0.1.2</version>
</dependency>
```

Build from source (repo root), then `install` to use it from your local repository:

```
./mvnw -B -pl client-java verify
./mvnw -B -pl client-java install
```

## Usage

```java
SenderClient client = SenderClient.builder()
        .baseUrl(URI.create("https://push.example.com"))
        .apiKey(System.getenv("RELIABLEPUSH_API_KEY"))   // per-app sender key
        .timeout(Duration.ofSeconds(10))                   // default; total time per call
        .build();

// 1. Generate the tracking id (reliable-push-payload: TrackingIds.newId()) and register BEFORE sending the push.
String tid = TrackingIds.newId();
Notification n = client.register(RegisterRequest.builder()
        .trackingId(tid)                                   // required: a UUID you generate
        .userId("user-42")
        .externalId("order-1001")                          // optional: unique per app, see below
        .policy(FallbackPolicy.NOT_READ)                   // optional: app default otherwise
        .timeoutMinutes(10)                                // optional: app default otherwise
        .fallback(Map.of("sms", "Your order shipped"))     // opaque; sent to your fallback webhook
        .build());

// 2. Put the same id into the push payload as `rp_tid` (here by hand; see "With reliable-push-payload").
String apns = "{\"aps\":{\"alert\":{\"title\":\"Shipped\",\"body\":\"...\"},\"mutable-content\":1},"
        + "\"rp_tid\":\"" + tid + "\"}";

// 3. Query or cancel later.
Optional<Notification> current = client.get(n.trackingId());      // empty when unknown
NotificationPage page = client.list("user-42", NotificationState.FALLBACK_PENDING, null, 20, null);
String next = page.nextCursor();                                   // opaque; null on the last page
client.cancel(n.trackingId());                                     // only PENDING/DELIVERED, else 409
```

`trackingId` is required and checked when the request is built (`NullPointerException` when missing,
`IllegalArgumentException` when it is not a UUID; it is lower-cased, as the backend returns it). `register` is idempotent per app on `trackingId`: repeating it
returns the existing record, so a retry after a lost response is safe. A repeated `externalId` under another
`trackingId` is a `409`. When the user has no active device the call still succeeds, with state
`NO_DEVICE_REGISTERED` (closed, not tracked, never falls back).

The timeout bounds the whole call (connect, headers and full body); on expiry you get status `-1`.
The base URL must not contain user info, query or fragment. HTTP/1.1 is used.

`list` takes `limit` 1..100 (otherwise `IllegalArgumentException`). A state this client does not know (newer
server) is reported as `NotificationState.UNKNOWN`.

## Registering before each send

Senders that register before every push should use a short per-client timeout and never let the registration block
the push:

```java
SenderClient client = SenderClient.builder()
        .baseUrl(URI.create("https://push.example.com")).apiKey(System.getenv("RELIABLEPUSH_API_KEY"))
        .timeout(Duration.ofSeconds(1))                    // recommended here; the default stays 10 s
        .build();

String tid = TrackingIds.newId();
try {
    client.register(RegisterRequest.builder().trackingId(tid).userId(userId).fallback(Map.of("sms", text)).build());
} catch (ReliablePushException e) {
    // informative only: log it and carry on
}
send(payloadWith(tid));                                    // ALWAYS send the push, with rp_tid, whatever happened
```

| `register` result | Push sent? | Tracking | Fallback |
|---|---|---|---|
| `201`, `PENDING` | yes, with `rp_tid` | yes | if the policy is unmet when due |
| `201`, `NO_DEVICE_REGISTERED` | yes, with `rp_tid` (harmless) | no | never; send your own SMS if you want one |
| `4xx` (validation, auth) | yes, with `rp_tid` | no | never; a sender bug to fix |
| timeout, `5xx`, network error | yes, with `rp_tid` | only if the row was stored | a lost response does not cause a duplicate fallback; otherwise a missed fallback |
| retry with the same `trackingId` | n/a | same row | once |

## With reliable-push-payload

The optional `reliable-push-payload` jar (zero dependencies, Java 8+) generates the id and builds the payload:

```java
String tid = TrackingIds.newId();
client.register(RegisterRequest.builder().trackingId(tid).userId(userId).fallback(Map.of("sms", text)).build());
String apns = ApnsPayloads.build(tid, "Shipped", "Order 1001", null);       // or ApnsPayloads.decorate(myJson, tid)
String fcm  = FcmPayloads.build(deviceToken, tid, "Shipped", "Order 1001", null); // or FcmPayloads.decorate(...)
```

## Error handling

Every failure is a `ReliablePushException` (unchecked):

- `status()`: the HTTP status (401, 404, 409, 413, 422, 429, 503, ...), or `-1` for I/O errors and timeouts.
  A redirect is never followed and surfaces as its status (for example 302).
- `problem()`: the parsed `application/problem+json` (`type`, `title`, `status`, `detail`, and `field` = the first
  invalid field of a 422), when the server sent one.
- `retryAfter()`: the `Retry-After` in seconds on 429 and 503. Retrying is up to the caller; `register` with an
  `externalId` is safe to retry.

`get` returns an empty `Optional` on 404; `cancel` throws on 404 and 409.

## Thread safety and secrets

`SenderClient` wraps one shared JDK `HttpClient` and holds no mutable state; call it from any thread. The API key
is sent only in the `X-Api-Key` header. **The API key is never logged**: it does not appear in `toString()`,
exception messages or causes, and the client does not log at all.
