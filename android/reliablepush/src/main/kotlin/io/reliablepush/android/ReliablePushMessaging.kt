package io.reliablepush.android

import android.content.Context
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM v1 entry point. Call it from the host's `FirebaseMessagingService.onMessageReceived`:
 *
 * ```
 * override fun onMessageReceived(message: RemoteMessage) {
 *     if (ReliablePushMessaging.onMessageReceived(this, message)) return
 *     // not a Reliable Push message (or it could not be shown): the host's own handling
 * }
 * ```
 *
 * `firebase-messaging` is a compile-only dependency of the SDK: the host app provides it.
 */
public object ReliablePushMessaging {
    /**
     * Delegates to [ReliablePush.handle] with the message data, using the message's notification title and body as
     * fallbacks for the `title`/`body` data keys. Returns what [ReliablePush.handle] returns.
     */
    public fun onMessageReceived(context: Context, message: RemoteMessage): Boolean =
        ReliablePush.handle(context, message.data, message.notification?.title, message.notification?.body)
}
