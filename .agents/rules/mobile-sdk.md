---
trigger: glob
globs: "android/**,ios/**"
description: Platform and store-policy rules for the Android and iOS SDKs.
---

# Mobile SDK rules

## Common
- Delivery tracking is best-effort: never block or delay showing the notification on the network call.
- Keep work short; no background polling, no periodic wakeups, no keep-alive tricks.
- Validate the device JWT expiry locally; refresh only when remaining validity < 20%.
- Queue failed events locally and retry later; batch when possible.
- Send only: trackingId, event type (DELIVERED / READ), timestamp, ids. Document data collection for App Store privacy labels and Play Data Safety.

## iOS
- Delivery is reported from a Notification Service Extension; requires `alert` + `"mutable-content": 1` in the payload. Always call the content handler (also in `serviceExtensionTimeWillExpire`).
- Share the device JWT between app and NSE via App Group + shared Keychain access group.
- READ is reported from `UNUserNotificationCenterDelegate` on tap.
- Ship `PrivacyInfo.xcprivacy`. Do not rely on silent (`content-available`) pushes.

## Android
- Mode v1 (default): data-only high-priority messages; the SDK reports DELIVERED and **always** posts a visible notification (no hidden high-priority messages).
- Mode v2 (opt-in): `AndroidConfigV2` `RemoteNotification` with `mutable_content: true`; implement `onNotificationReceived` and return a `NotificationResponse` within 20 s. Do not use suppression to hide tracking work.
- Longer work goes to WorkManager. Respect Android 13+ `POST_NOTIFICATIONS` permission.
