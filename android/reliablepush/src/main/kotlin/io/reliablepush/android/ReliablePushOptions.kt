package io.reliablepush.android

import android.app.Activity
import android.app.Notification

/**
 * Options for [ReliablePush.configure]. Only the base URL and app id are persisted; options apply to the running
 * process, so pass the same ones on every launch.
 *
 * @property channelId notification channel the SDK posts to; created on the first post unless the host created it.
 * @property channelName user-visible name of that channel when the SDK creates it.
 * @property smallIcon drawable resource of the notification's small icon; `0` uses the app icon.
 * @property launchActivity activity opened when the notification is tapped; `null` uses the launcher activity. It
 *   receives the extra `rp_tid`: call [ReliablePush.trackOpen] from its `onCreate` and `onNewIntent`.
 * @property jobId id of the SDK's one-off `JobScheduler` retry job; change it only if it clashes with a host job.
 * @property customizer called with the notification builder and the `rp_tid` before the notification is built, to
 *   add colour, actions, a large icon… It must not hide the notification (store policy: every tracked push is shown).
 */
public data class ReliablePushOptions(
    val channelId: String = "reliablepush_default",
    val channelName: String = "Notifications",
    val smallIcon: Int = 0,
    val launchActivity: Class<out Activity>? = null,
    val jobId: Int = 0x52500001,
    val customizer: ((Notification.Builder, String) -> Unit)? = null,
)
