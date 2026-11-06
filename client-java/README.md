# reliable-push client (Java)

Zero-dependency (JDK 17+, `java.net.http`) client for the reliable-push **sender API**: register, get, list and
cancel notifications. Instances are immutable and thread-safe: create one `SenderClient` and share it.

## Install

Maven coordinates (not yet published to a repository):

```xml
<dependency>
  <groupId>io.reliablepush</groupId>
  <artifactId>reliable-push-client</artifactId>
  <version>0.1.0</version>
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

// 1. Register before (or right after) sending the push.
Notification n = client.register(RegisterRequest.builder()
        .userId("user-42")
        .externalId("order-1001")                          // optional: makes the call idempotent
        .policy(FallbackPolicy.NOT_READ)                   // optional: app default otherwise
        .timeoutMinutes(10)                                // optional: app default otherwise
        .fallback(Map.of("sms", "Your order shipped"))     // opaque; sent to your fallback webhook
        .build());

// 2. Put the trackingId into the push payload as `rp_tid`.
String apns = "{\"aps\":{\"alert\":{\"title\":\"Shipped\",\"body\":\"...\"},\"mutable-content\":1},"
        + "\"rp_tid\":\"" + n.trackingId() + "\"}";

// 3. Query or cancel later.
Optional<Notification> current = client.get(n.trackingId());      // empty when unknown
NotificationPage page = client.list("user-42", NotificationState.FALLBACK_PENDING, null, 20, null);
String next = page.nextCursor();                                   // opaque; null on the last page
client.cancel(n.trackingId());                                     // only PENDING/DELIVERED, else 409
```

The timeout bounds the whole call (connect, headers and full body); on expiry you get status `-1`.
The base URL must not contain user info, query or fragment. HTTP/1.1 is used.

`list` takes `limit` 1..100 (otherwise `IllegalArgumentException`). A state this client does not know (newer
server) is reported as `NotificationState.UNKNOWN`.

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
