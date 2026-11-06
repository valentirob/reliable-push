package io.reliablepush.android.internal

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.reliablepush.android.ReliablePushOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

class TestLaunchActivity : Activity()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NotificationPosterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val tid = "0b6f7c2e-8a7d-4a43-9d8e-6c1f0a2b3c4d"
    private val tid2 = "1c7a8d3f-9b8e-4b54-8e9f-7d2a1b3c4d5e"

    private fun posted(id: String = tid): Notification? = shadowOf(nm).getNotification("rp", id.hashCode())

    private fun contentIntentOf(n: Notification): Intent = shadowOf(n.contentIntent).savedIntent

    @Test
    fun postsOnTheSdkChannelWithTagIdAndTapIntent() {
        val options = ReliablePushOptions(launchActivity = TestLaunchActivity::class.java, smallIcon = android.R.drawable.ic_dialog_email)
        assertTrue(NotificationPoster(context, options).post(tid, "Hello", "World"))

        val channel = nm.getNotificationChannel("reliablepush_default")
        assertNotNull(channel)
        assertEquals("Notifications", channel.name)

        val n = posted()!!
        assertEquals("reliablepush_default", n.channelId)
        assertEquals("Hello", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("World", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(android.R.drawable.ic_dialog_email, n.smallIcon.resId)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)

        val pi = shadowOf(n.contentIntent)
        assertTrue(pi.isActivity)
        assertTrue(pi.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = contentIntentOf(n)
        assertEquals(ComponentName(context, TestLaunchActivity::class.java), intent.component)
        assertEquals(tid, intent.getStringExtra("rp_tid"))
    }

    @Test
    fun defaultsToTheLauncherActivityAndTheAppIcon() {
        val launcher = ComponentName(context.packageName, TestLaunchActivity::class.java.name)
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(launcher)
            addIntentFilterForActivity(
                launcher,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
        context.applicationInfo.icon = android.R.drawable.ic_dialog_alert

        assertTrue(NotificationPoster(context, ReliablePushOptions()).post(tid, "t", "b"))

        val n = posted()!!
        assertEquals(android.R.drawable.ic_dialog_alert, n.smallIcon.resId)
        assertEquals(launcher, contentIntentOf(n).component)
    }

    @Test
    fun differentTrackingIdsKeepTheirOwnNotificationAndExtras() {
        val poster = NotificationPoster(context, ReliablePushOptions(launchActivity = TestLaunchActivity::class.java))
        assertTrue(poster.post(tid, "a", "1"))
        assertTrue(poster.post(tid2, "b", "2"))

        assertEquals(2, shadowOf(nm).allNotifications.size)
        val first = posted(tid)!!
        val second = posted(tid2)!!
        assertNotEquals(shadowOf(first.contentIntent).requestCode, shadowOf(second.contentIntent).requestCode)
        assertEquals(tid, contentIntentOf(first).getStringExtra("rp_tid"))
        assertEquals(tid2, contentIntentOf(second).getStringExtra("rp_tid"))
    }

    @Test
    fun customizerRunsWithTheTrackingId() {
        var seen: String? = null
        val options = ReliablePushOptions(
            launchActivity = TestLaunchActivity::class.java,
            customizer = { builder, id -> seen = id; builder.setSubText("custom") },
        )
        assertTrue(NotificationPoster(context, options).post(tid, "t", "b"))
        assertEquals(tid, seen)
        assertEquals("custom", posted()!!.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
    }

    @Test
    fun failingCustomizerStillPostsTheNotification() {
        val options = ReliablePushOptions(
            launchActivity = TestLaunchActivity::class.java,
            customizer = { _, _ -> throw IllegalStateException("host bug") },
        )
        assertTrue(NotificationPoster(context, options).post(tid, "t", "b"))
        assertNotNull(posted())
    }

    @Test
    fun customizerThrowingAnErrorStillPostsTheNotification() {
        val options = ReliablePushOptions(
            launchActivity = TestLaunchActivity::class.java,
            customizer = { _, _ -> throw AssertionError("host bug") },
        )
        assertTrue(NotificationPoster(context, options).post(tid, "t", "b"))
        assertNotNull(posted())
    }

    @Test
    fun customizerMovingToABlockedOrMissingChannelPostsNothing() {
        nm.createNotificationChannel(NotificationChannel("muted", "Muted", NotificationManager.IMPORTANCE_NONE))
        val toMuted = ReliablePushOptions(customizer = { b, _ -> b.setChannelId("muted") })
        assertFalse(NotificationPoster(context, toMuted).post(tid, "t", "b"))
        val toMissing = ReliablePushOptions(customizer = { b, _ -> b.setChannelId("does_not_exist") })
        assertFalse(NotificationPoster(context, toMissing).post(tid, "t", "b"))
        assertNull(posted())

        nm.createNotificationChannel(NotificationChannel("loud", "Loud", NotificationManager.IMPORTANCE_HIGH))
        val toLoud = ReliablePushOptions(customizer = { b, _ -> b.setChannelId("loud") })
        assertTrue(NotificationPoster(context, toLoud).post(tid, "t", "b"))
        assertEquals("loud", posted()!!.channelId)
    }

    @Test
    fun missingLaunchActivityIsLoggedButStillPosts() {
        ShadowLog.clear()
        assertTrue(NotificationPoster(context, ReliablePushOptions()).post(tid, "t", "b"))
        assertNull(posted()!!.contentIntent)
        assertTrue(
            ShadowLog.getLogsForTag("ReliablePush")
                .any { it.type == Log.WARN && it.msg == "event=notification.no_launch_activity tid=$tid" },
        )
    }

    @Test
    fun appNotificationsDisabledPostsNothing() {
        shadowOf(nm).setNotificationsEnabled(false)
        assertFalse(NotificationPoster(context, ReliablePushOptions()).post(tid, "t", "b"))
        assertNull(posted())
    }

    @Test
    fun channelSetToNonePostsNothing() {
        nm.createNotificationChannel(NotificationChannel("reliablepush_default", "Off", NotificationManager.IMPORTANCE_NONE))
        assertFalse(NotificationPoster(context, ReliablePushOptions()).post(tid, "t", "b"))
        assertNull(posted())
    }

    @Test
    fun blockedChannelGroupPostsNothing() {
        val group = NotificationChannelGroup("g", "Group")
        NotificationChannelGroup::class.java.getMethod("setBlocked", Boolean::class.javaPrimitiveType).invoke(group, true)
        nm.createNotificationChannelGroup(group)
        val channel = NotificationChannel("reliablepush_default", "Notifications", NotificationManager.IMPORTANCE_DEFAULT)
        channel.group = "g"
        nm.createNotificationChannel(channel)
        assertFalse(NotificationPoster(context, ReliablePushOptions()).post(tid, "t", "b"))
        assertNull(posted())
    }

    @Test
    fun unblockedChannelGroupStillPosts() {
        nm.createNotificationChannelGroup(NotificationChannelGroup("g", "Group"))
        val channel = NotificationChannel("reliablepush_default", "Notifications", NotificationManager.IMPORTANCE_DEFAULT)
        channel.group = "g"
        nm.createNotificationChannel(channel)
        assertTrue(NotificationPoster(context, ReliablePushOptions()).post(tid, "t", "b"))
    }

    @Test
    fun pausedNotificationsPostNothing() {
        assertFalse(NotificationPoster(context, ReliablePushOptions(), paused = { true }).post(tid, "t", "b"))
        assertNull(posted())
        assertTrue(NotificationPoster(context, ReliablePushOptions(), paused = { false }).post(tid, "t", "b"))
    }
}
