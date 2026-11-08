# reliable-push and the market

How reliable-push compares with the products that confirm push delivery or fall back to another channel. Every claim
about another product comes from that vendor's public documentation, read on 2026-11-09; "not documented" means we found
no public description, not that the feature is missing. Ask the vendor before deciding, and update this page (and the
"Market comparison" slide of `presentation.html`) when you learn more.

| | reliable-push | Catapush | OneSignal | Airship | Braze |
|---|---|---|---|---|---|
| Delivery confirmed by the device | yes: the SDK, from the iOS Notification Service Extension and the Android messaging service | yes: per-message status (sent, delivered, read) [1][2] | yes: "Confirmed Delivery", paid plans; on iOS it needs the Notification Service Extension [4] | not documented: push service reports [7] | not documented: the provider's acceptance [8] |
| Read / open tracking | yes: `READ` on tap | yes [2] | yes: clicks [4] | yes: opens | yes: opens |
| Fallback when not confirmed in time | yes: per push, policy and timeout, signed webhook | yes: SMS to the users who did not receive the push, after its expiry [1] | yes: Journeys "Wait Until" confirmed delivery or click, then SMS or email [5] | partly: "Priority Channel with Fallback" moves to the next channel when the push channel is unreachable (an uninstall found at send time) [6] | partly: a Canvas can branch on "not opened" and send an SMS; an undelivered push and an unopened one look the same [8] |
| Fallback channel | yours: any SMS, email or other provider behind your webhook | Catapush SMS, optional, prepaid and billed apart [3] | OneSignal SMS and email [5] | Airship channels [6] | Braze channels |
| Who sends the push | you, unchanged: a sidecar; your FCM and APNs keys stay with you | Catapush: its own XMPP channel, native push only to wake the app [9] | OneSignal | Airship | Braze |
| Guard against a fallback storm when tracking breaks | yes: per-app circuit breaker | not documented | not documented | not documented | not documented |
| Hosting and data | self-hosted: your cluster, your PostgreSQL | SaaS | SaaS | SaaS | SaaS |
| Licence and cost | MIT, free; you run it | free tier, then per monthly unique active recipient, on quote [3] | free plan; Confirmed Delivery on paid plans [4] | on request | on request |
| Campaigns, segments, in-app messages, analytics | no, by design | messaging focus | yes | yes | yes |

## Where it fits

- **reliable-push**: your backend already sends pushes and should keep doing so; you need confirmed delivery (or read)
  and a fallback within minutes; the data stays with you and you choose the SMS or email provider; you can add it and
  remove it without touching the push path.
- **Catapush**: a managed channel with delivery receipts and SMS fallback included, if you can move sending to it.
- **OneSignal**: confirmed delivery plus campaigns and journeys in one SaaS, on a paid plan.
- **Airship, Braze**: full engagement platforms; their fallback follows reachability or opens, not a confirmed delivery.

Limits of reliable-push: it only tracks and falls back (no campaigns, segmentation, in-app messages or hosted service),
it needs its SDK in the app and the payload change in the sender, and delivery through real APNs and FCM is not verified
yet (`docs/TODO.md` section 1).

## Sources

1. Catapush, [SMS fallback](https://catapush.com/features/sms-fallback)
2. Catapush, [Dispatch reliability: queuing, retry, expiry and status reports](https://catapush.com/features/dispatch-reliability)
3. Catapush, [Pricing](https://catapush.com/pricing)
4. OneSignal, [Confirmed Delivery](https://documentation.onesignal.com/docs/confirmed-delivery)
5. OneSignal, [Fallback messages: email or SMS when push is unavailable](https://documentation.onesignal.com/docs/push-fallback-method)
6. Airship, [Channel Coordination](https://www.airship.com/docs/guides/features/orchestration-experimentation/channel-coordination/)
7. Airship, [Message delivery options](https://www.airship.com/docs/guides/messaging/messages/delivery/delivery-options)
8. Braze, [Troubleshoot push notifications](https://braze.com/docs/developer_guide/push_notifications/troubleshooting/) and [Canvas not sending](https://braze.com/docs/help/help_articles/campaigns_and_canvas/canvas_not_sending)
9. Catapush, [Architecture and operating modes](https://www.catapush.com/docs-quickstart)
