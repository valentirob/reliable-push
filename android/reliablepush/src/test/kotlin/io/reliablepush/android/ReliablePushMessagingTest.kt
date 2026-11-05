package io.reliablepush.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import io.reliablepush.android.internal.Sdk
import io.reliablepush.android.internal.TestLaunchActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReliablePushMessagingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val tid = "0b6f7c2e-8a7d-4a43-9d8e-6c1f0a2b3c4d"

    @Before
    fun setUp() {
        ReliablePush.resetForTest()
        Sdk.cipherFactory = { FakeCipher() }
        ReliablePush.configure(
            context, "https://push.example.com", "news", ReliablePushOptions(launchActivity = TestLaunchActivity::class.java),
        )
    }

    @After
    fun tearDown() {
        settle()
        ReliablePush.resetForTest()
    }

    /** A message as FCM hands it over: data keys plain, notification keys under `gcm.n.*`. */
    private fun message(data: Map<String, String>, title: String? = null, body: String? = null): RemoteMessage {
        val b = Bundle()
        data.forEach { (k, v) -> b.putString(k, v) }
        if (title != null || body != null) b.putString("gcm.n.e", "1")
        title?.let { b.putString("gcm.n.title", it) }
        body?.let { b.putString("gcm.n.body", it) }
        return RemoteMessage(b)
    }

    private fun shown(): Notification = shadowOf(nm).getNotification("rp", tid.hashCode())

    @Test
    fun messagingAdapterUsesNotificationFieldsAsFallback() {
        assertTrue(ReliablePushMessaging.onMessageReceived(context, message(mapOf("rp_tid" to tid), "N title", "N body")))
        assertEquals("N title", shown().extras.getString(Notification.EXTRA_TITLE))
        assertEquals("N body", shown().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun dataFieldsWinOverNotificationFields() {
        val msg = message(mapOf("rp_tid" to tid, "title" to "D title", "body" to "D body"), "N title", "N body")
        assertTrue(ReliablePushMessaging.onMessageReceived(context, msg))
        assertEquals("D title", shown().extras.getString(Notification.EXTRA_TITLE))
        assertEquals("D body", shown().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun foreignMessageIsLeftToTheHost() {
        assertFalse(ReliablePushMessaging.onMessageReceived(context, message(mapOf("k" to "v"), "t", "b")))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
}
