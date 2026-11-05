package io.reliablepush.sample

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

/**
 * The FCM registration token, when Firebase is configured. Without `google-services.json` there is no default
 * FirebaseApp and `FirebaseMessaging.getInstance()` would throw, so every Firebase call is guarded: no app, no token,
 * no crash.
 */
internal object FcmTokens {
    /** True when a default FirebaseApp exists (the build had `google-services.json`). */
    fun configured(context: Context): Boolean = try {
        FirebaseApp.getApps(context).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /** The token, or null when Firebase is not configured or the token could not be fetched in 10 s. Blocking. */
    fun currentBlocking(context: Context): String? {
        if (!configured(context)) return null
        return try {
            Tasks.await(FirebaseMessaging.getInstance().token, 10, TimeUnit.SECONDS)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
