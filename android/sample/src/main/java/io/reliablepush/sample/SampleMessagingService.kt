package io.reliablepush.sample

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.reliablepush.android.ReliablePushMessaging

/** FCM entry point: Reliable Push messages go to the SDK (it posts and tracks them); anything else is the app's. */
class SampleMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (ReliablePushMessaging.onMessageReceived(this, message)) return
        // not a Reliable Push message, or notifications are blocked: the app's own handling would go here
    }
}
