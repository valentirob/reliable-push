package io.reliablepush.sample

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import io.reliablepush.android.ReliablePush
import io.reliablepush.android.ReliablePushMessaging

/** FCM entry point: Reliable Push messages go to the SDK (it posts and tracks them); anything else is the app's. */
class SampleMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (ReliablePushMessaging.onMessageReceived(this, message)) return
        // not a Reliable Push message, or notifications are blocked: the app's own handling would go here
    }

    /**
     * Debug build: the harness's real-FCM mode needs the current registration token, so a changed one is reported for
     * the registered device (a release app would send it to its own backend). Never logged.
     */
    override fun onNewToken(token: String) {
        if (!BuildConfig.DEBUG) return
        Thread({
            try {
                val backend = SampleBackend.load() ?: return@Thread
                val deviceId = ReliablePush.deviceId ?: return@Thread // not registered yet: register reports the token
                backend.fcmTokenChanged(deviceId, token)
            } catch (e: Exception) {
                Log.w("ReliablePushSample", "FCM token report failed: ${e.javaClass.simpleName}")
            }
        }, "sample-fcm-token").start()
    }
}
