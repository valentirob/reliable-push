# reliable-push-payload

A zero-dependency, **Java 8** library for senders that use reliable-push as a sidecar. It generates tracking ids and
builds or decorates the tracked APNs and FCM payloads, so your backend does not need to know the payload rules
(`rp_tid`, `mutable-content`, data-only FCM with high priority, size limits). No network, no threads, no reflection;
every class is immutable and thread-safe. Tests run on Java 8 and Java 21.

```xml
<dependency>
  <groupId>io.reliablepush</groupId>
  <artifactId>reliable-push-payload</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

Package `io.reliablepush.payload`: `TrackingIds`, `ApnsPayloads`, `FcmPayloads`, `PayloadException`. The package
`io.reliablepush.payload.internal` is not API.

## The flow

1. `String trackingId = TrackingIds.newId();`
2. Build or decorate the payload (below).
3. Register the notification with reliable-push (for example `client-java`, with `trackingId` and a short timeout).
4. Send the push yourself, with your own APNs/FCM client.

## Build a payload

```java
String tid = TrackingIds.newId();

// APNs: {"aps":{"alert":{"title":..,"body":..},"mutable-content":1},"rp_tid":..,<custom>}
String apns = ApnsPayloads.build(tid, "Order shipped", "Your order 1001 is on its way.",
        Collections.singletonMap("orderId", 1001));

// FCM HTTP v1 request body: data-only, android.priority HIGH, data {rp_tid, title, body, custom...}
String fcm = FcmPayloads.build(fcmToken, tid, "Order shipped", "Your order 1001 is on its way.",
        Collections.singletonMap("orderId", "1001"));
```

## Decorate your own payload

Keep your existing payload code and let the library add what tracking needs. Keys keep their order, `rp_tid` and
`mutable-content` are appended, numbers are written as given.

```java
String apns = ApnsPayloads.decorate(myApnsJson, tid);   // needs a visible aps.alert
String fcm  = FcmPayloads.decorate(myFcmV1Json, tid);    // {"message":{...}} with notification + data
```

`FcmPayloads.decorate` moves `message.notification.title/body` and `message.android.notification.title/body` (the
Android one wins) into `message.data`, removes both `notification` objects, sets `message.android.priority` to `HIGH`
and adds `rp_tid`. Everything else (`token`/`topic`, `android.ttl`, other `data` keys) stays as it is. A message with
no visible title or body (a silent data-only message) is rejected with `FCM_NO_NOTIFICATION`: the SDK would turn it
into a blank notification, so send it untracked.

Tracking ids are valid in any case; every builder embeds `rp_tid` **lower-cased**, the form the backend stores and
echoes and the SDKs match on.

## Firebase Admin SDK

```java
Message message = Message.builder()
        .setToken(fcmToken)
        .putAllData(FcmPayloads.data(tid, "Order shipped", "Your order 1001 is on its way.", extra))
        .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
        .build();                       // no setNotification(...): the SDK posts the notification from data
FirebaseMessaging.getInstance().send(message);
```

## Raw APNs HTTP/2

```java
String body = ApnsPayloads.build(tid, "Order shipped", "Your order 1001 is on its way.", null);
// POST https://api.push.apple.com/3/device/<token>  with apns-push-type: alert and the UTF-8 bytes of body
```

## Errors: catch `PayloadException`, send untracked

Nothing is dropped or fixed silently: a payload that cannot be tracked throws `PayloadException` (an
`IllegalArgumentException`) with a `code()`. Catch it, skip the registration (or cancel it with
`DELETE /v1/notifications/{trackingId}`) and send the push **untracked**, as you did before. A tracked push that cannot
be sent must never become a fallback.

```java
try {
    payload = ApnsPayloads.decorate(myApnsJson, tid);
    // register tid with reliable-push
} catch (PayloadException e) {
    log.warn("sending untracked: {} {}", e.code(), e.getMessage());
    payload = myApnsJson;
}
```

| Code | When |
|---|---|
| `INVALID_TRACKING_ID` | not a UUID |
| `RESERVED_KEY` | your data or custom keys contain `rp_tid` (any platform), `title` or `body` (FCM data), or `aps` (APNs custom) |
| `APNS_NO_ALERT` | no visible `aps.alert` (a non-empty string, or an object with a non-empty `title` or `body`), for example a `content-available`-only payload |
| `APNS_TOO_LARGE` | the payload is above 4,096 bytes (UTF-8 length of the produced JSON) |
| `FCM_NON_STRING_DATA` | an FCM data value (or a notification title/body) is not a string |
| `FCM_UNSUPPORTED_NOTIFICATION_FIELD` | `FcmPayloads.decorate`: `notification` or `android.notification` holds a field other than `title` and `body` (image, channel, click action...); the message lists them |
| `FCM_NO_NOTIFICATION` | the FCM message would carry no visible title or body (both null or empty, for example a silent data-only message): the SDK shows every tracked message, so send it untracked |
| `FCM_TOO_LARGE` | the FCM `data` map is above 4,096 bytes: the UTF-8 length of its compact JSON serialization, so a slightly conservative bound; the rest of the message is not counted |
| `INVALID_JSON` | `decorate`: the input is not a JSON object of the expected shape (also: duplicate keys, nesting beyond 64 levels) |

A localized alert (only `loc-key` / `title-loc-key`, no literal `title` or `body`) is rejected with `APNS_NO_ALERT`:
the Notification Service Extension cannot report it as seen, so send that push untracked (no `rp_tid`).
The map variant `ApnsPayloads.decorate(Map, tid)` returns a deeply unmodifiable copy.

## Output format

Compact JSON, keys in input order, reliable-push keys appended, numbers as given, strings escaped per RFC 8259 and
non-ASCII kept as is: send the result as UTF-8.

## Golden vectors for other languages

`src/test/resources/vectors/*.json` hold input and exact expected output (or error code) for every operation. The jar's
tests run all of them; a Node, .NET or PHP backend can implement the same rules and check itself against the same files.
Format: [`src/test/resources/vectors/README.md`](src/test/resources/vectors/README.md).

## Build

```bash
./mvnw -B -pl payload-java verify     # from the repository root; JDK 21 compiles with --release 8
```

CI also runs the tests on a Java 8 JVM (Maven runs on 21 and Surefire forks Java 8 with `-Djvm=<path to java 8>`).
