package io.reliablepush.android.internal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.reliablepush.android.ReliablePushOptions

/**
 * Posts the visible notification of a tracked push (mode v1: the FCM message is data-only and the SDK posts a local
 * notification, as every confirmed-delivery service does on Android).
 *
 * `handle` calls it in three steps (spec §3.4):
 * 1. [canShow], the gate, before `DELIVERED` is persisted. It never requests permissions: it checks that the app may
 *    show notifications ([NotificationManager.areNotificationsEnabled], false also when `POST_NOTIFICATIONS` is denied
 *    on Android 13+), that notifications are not paused for the app (API 29+, e.g. a suspended app), and that the SDK
 *    channel ([ReliablePushOptions.channelId]) exists, is not set to [NotificationManager.IMPORTANCE_NONE] and does
 *    not belong to a blocked channel group (API 28+). The SDK channel is created here when missing, with
 *    [NotificationManager.IMPORTANCE_DEFAULT]; a host that creates it first keeps its own settings.
 * 2. [post]: builds the notification (with the host's customizer) and hands it to the system. If the customizer moved
 *    it to another channel, that channel is checked too, as the system would drop it silently.
 * 3. [postMinimal], only when something threw before or in [post]: the simplest notification that can be built.
 *
 * Errors of the platform (e.g. a `SecurityException`) propagate from all three, so `handle` can fall back to the
 * minimal notification; only a host customizer's errors are caught here.
 *
 * Notification: tag [TAG], id `rp_tid.hashCode()`, auto-cancel, content intent = an immutable activity
 * [PendingIntent] on [ReliablePushOptions.launchActivity] (or the launcher activity) with extra [EXTRA_TRACKING_ID],
 * whose request code is also `rp_tid.hashCode()` so two notifications do not share (and overwrite) one intent.
 *
 * Open, with open steps, for the fault-injection tests of `handle`.
 */
internal open class NotificationPoster(
    context: Context,
    private val options: ReliablePushOptions,
    /** Seam over [NotificationManager.areNotificationsPaused] (API 29+), which Robolectric cannot shadow. */
    private val paused: (NotificationManager) -> Boolean = { notificationsPaused(it) },
) {
    private val context: Context = context.applicationContext

    /** The gate: true when a notification on the SDK channel would be shown; false with a WARN otherwise. */
    open fun canShow(): Boolean {
        val nm = manager() ?: return closed("no_manager")
        if (!nm.areNotificationsEnabled()) return closed("notifications_disabled")
        if (paused(nm)) return closed("notifications_paused")
        ensureChannel(nm)
        val channel = nm.getNotificationChannel(options.channelId) ?: return closed("channel_missing")
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return closed("channel_blocked")
        if (groupBlocked(nm, channel)) return closed("channel_group_blocked")
        return true
    }

    /**
     * Builds and posts the notification. True when it was handed to the system; false (WARN) when there is no
     * notification manager or the customizer moved it to a missing or blocked channel.
     */
    open fun post(trackingId: String, title: String, body: String): Boolean {
        val nm = manager() ?: return notPosted(trackingId, "no_manager")
        ensureChannel(nm)
        val notification = build(trackingId, title, body)
        if (notification.channelId != options.channelId) { // moved by the customizer
            val channel = nm.getNotificationChannel(notification.channelId)
                ?: return notPosted(trackingId, "channel_missing") // the system would drop it silently
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return notPosted(trackingId, "channel_blocked")
            if (groupBlocked(nm, channel)) return notPosted(trackingId, "channel_group_blocked")
        }
        nm.notify(TAG, trackingId.hashCode(), notification)
        return true
    }

    /**
     * The last resort of `handle`: [title] and [body] on the SDK channel (created if missing, best effort), the small
     * icon and the tap intent with `rp_tid`; no style and no customizer. Throws when even this cannot be posted.
     */
    open fun postMinimal(trackingId: String, title: String, body: String) {
        val nm = checkNotNull(manager()) { "no NotificationManager" }
        try {
            ensureChannel(nm)
        } catch (e: RuntimeException) { // post anyway: the channel may exist
            SdkLog.warn("notification.channel_failed", "tid" to trackingId, "error" to e.javaClass.simpleName)
        }
        val builder = Notification.Builder(context, options.channelId)
            .setSmallIcon(smallIcon())
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
        try {
            contentIntent(trackingId)?.let { builder.setContentIntent(it) }
        } catch (e: RuntimeException) { // shown without a tap action rather than not at all
            SdkLog.warn("notification.no_launch_activity", "tid" to trackingId, "error" to e.javaClass.simpleName)
        }
        nm.notify(TAG, trackingId.hashCode(), builder.build())
    }

    private fun manager(): NotificationManager? = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(options.channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(options.channelId, options.channelName, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun closed(reason: String): Boolean {
        SdkLog.warn("notification.not_shown", "reason" to reason)
        return false
    }

    private fun groupBlocked(nm: NotificationManager, channel: NotificationChannel): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val group = channel.group ?: return false
        return nm.getNotificationChannelGroup(group)?.isBlocked == true
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
            } catch (e: Throwable) { // a host bug (even an Error) must not crash the push service or hide the notification
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
        if (intent == null) { // a tap then opens nothing and READ cannot be reported
            SdkLog.warn("notification.no_launch_activity", "tid" to trackingId)
            return null
        }
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
        fun notificationsPaused(nm: NotificationManager): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && nm.areNotificationsPaused()

        const val TAG = "rp"
        const val EXTRA_TRACKING_ID = "rp_tid"
    }
}
