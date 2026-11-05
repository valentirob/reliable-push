package io.reliablepush.android.internal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.reliablepush.android.ReliablePushOptions

/**
 * Posts the visible notification of a tracked push (mode v1: every tracked push is shown, never hidden).
 *
 * It never requests permissions: it only checks that the app may show notifications
 * ([NotificationManager.areNotificationsEnabled], false also when `POST_NOTIFICATIONS` is denied on Android 13+) and
 * that the SDK channel is not set to [NotificationManager.IMPORTANCE_NONE]. The channel ([ReliablePushOptions.channelId])
 * is created on first post with [NotificationManager.IMPORTANCE_DEFAULT]; a host that creates it first keeps its
 * own settings.
 *
 * Notification: tag [TAG], id `rp_tid.hashCode()`, auto-cancel, content intent = an immutable activity
 * [PendingIntent] on [ReliablePushOptions.launchActivity] (or the launcher activity) with extra [EXTRA_TRACKING_ID],
 * whose request code is also `rp_tid.hashCode()` so two notifications do not share (and overwrite) one intent.
 */
internal class NotificationPoster(context: Context, private val options: ReliablePushOptions) {
    private val context: Context = context.applicationContext

    /** True only when the notification was handed to the system; any failure is logged (WARN) and returns false. */
    fun post(trackingId: String, title: String, body: String): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return notPosted(trackingId, "no_manager")
        return try {
            if (!nm.areNotificationsEnabled()) return notPosted(trackingId, "notifications_disabled")
            if (nm.getNotificationChannel(options.channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(options.channelId, options.channelName, NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val channel = nm.getNotificationChannel(options.channelId)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return notPosted(trackingId, "channel_blocked")
            }
            nm.notify(TAG, trackingId.hashCode(), build(trackingId, title, body))
            true
        } catch (e: RuntimeException) { // e.g. SecurityException if the permission is revoked meanwhile
            notPosted(trackingId, "error")
        }
    }

    private fun build(trackingId: String, title: String, body: String): Notification {
        val builder = Notification.Builder(context, options.channelId)
            .setSmallIcon(smallIcon())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
        contentIntent(trackingId)?.let { builder.setContentIntent(it) }
        options.customizer?.let { customize ->
            try {
                customize(builder, trackingId)
            } catch (e: Exception) { // a host bug must not cost the user the notification
                SdkLog.warn("notification.customizer_failed", "tid" to trackingId, "error" to e.javaClass.simpleName)
            }
        }
        return builder.build()
    }

    private fun smallIcon(): Int = when {
        options.smallIcon != 0 -> options.smallIcon
        context.applicationInfo.icon != 0 -> context.applicationInfo.icon
        else -> android.R.drawable.sym_def_app_icon
    }

    private fun contentIntent(trackingId: String): PendingIntent? {
        val intent = options.launchActivity?.let { Intent(context, it) }
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        intent.putExtra(EXTRA_TRACKING_ID, trackingId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            trackingId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notPosted(trackingId: String, reason: String): Boolean {
        SdkLog.warn("notification.not_posted", "tid" to trackingId, "reason" to reason)
        return false
    }

    companion object {
        const val TAG = "rp"
        const val EXTRA_TRACKING_ID = "rp_tid"
    }
}
