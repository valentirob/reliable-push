# Golden vectors

Language-neutral test cases for the reliable-push payload rules. The Java jar (`payload-java`) runs every file in this
directory in `GoldenVectorsTest`; a Node, .NET, PHP or other backend that implements the same rules can run the same
files against its own implementation and must produce the same bytes.

## File format

One JSON file per case, named `<name>.json`:

```json
{
  "name": "apns-build-basic",
  "description": "optional, free text",
  "operation": "apns.build",
  "input": { "...": "arguments of the operation" },
  "expect": { "output": "..." }
}
```

`expect` is either `{"output": <JSON value>}` or `{"error": "<CODE>"}`. For an error, the operation must fail with
exactly that code (the `PayloadException.Code` names: `INVALID_TRACKING_ID`, `RESERVED_KEY`, `APNS_NO_ALERT`,
`APNS_TOO_LARGE`, `FCM_NON_STRING_DATA`, `FCM_UNSUPPORTED_NOTIFICATION_FIELD`, `FCM_TOO_LARGE`, `INVALID_JSON`).

## Operations

| `operation` | `input` | `output` |
|---|---|---|
| `trackingIds.isValid` | `id` (string or null) | boolean |
| `apns.build` | `trackingId`, `title`, `body`, optional `custom` (object) | the APNs payload (object) |
| `apns.decorate` | `trackingId` and either `apns` (the sender's payload as an object) or `apnsJson` (the payload as a raw string, for malformed input) | the decorated APNs payload |
| `fcm.build` | `token`, `trackingId`, `title`, `body`, optional `data` (object) | the FCM HTTP v1 request body (object) |
| `fcm.data` | `trackingId`, `title`, `body`, optional `data` (object) | the data map (object) |
| `fcm.decorate` | `trackingId` and either `fcm` (the sender's request body as an object) or `fcmJson` (raw string) | the decorated request body |

## Comparing results

Serialize your result as compact JSON (no whitespace) with the keys in the order you produced them and compare it with
the compact serialization of `expect.output`, key order included: **order is part of the contract** (the sender's keys
keep their order, reliable-push keys are appended). Numbers are written as given (`1.50` stays `1.50`). Strings are
escaped per RFC 8259 (control characters as `\n`, `\t`... or `\u00XX`, `"` and `\`), everything else, including
non-ASCII, is written as UTF-8. Sizes are measured in UTF-8 bytes of the produced JSON: an APNs payload above 4,096
bytes is `APNS_TOO_LARGE`; for FCM, the compact JSON of the `data` map above 4,096 bytes is `FCM_TOO_LARGE`.

When several rules fail at once, the first of this order wins: `INVALID_TRACKING_ID`, `INVALID_JSON`, then the
content rules (`RESERVED_KEY`, `APNS_NO_ALERT`, `FCM_UNSUPPORTED_NOTIFICATION_FIELD`, `FCM_NON_STRING_DATA`), then
the size rule.
