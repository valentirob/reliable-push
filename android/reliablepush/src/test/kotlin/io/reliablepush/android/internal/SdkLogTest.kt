package io.reliablepush.android.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SdkLogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalFlags = context.applicationInfo.flags

    @Before
    fun clean() = ShadowLog.clear()

    @After
    fun restore() {
        context.applicationInfo.flags = originalFlags
        SdkLog.init(context)
    }

    private fun lines() = ShadowLog.getLogsForTag("ReliablePush")

    @Test
    fun releaseBuildLogsOnlyWarnAndAbove() {
        context.applicationInfo.flags = originalFlags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        SdkLog.init(context)

        SdkLog.debug("x.debug")
        SdkLog.info("tracking.event_sent", "count" to 1)
        SdkLog.warn("tracking.not_registered", "tid" to "t-1")
        SdkLog.error("sdk.failed", "error" to "IllegalStateException")

        assertEquals(listOf(Log.WARN, Log.ERROR), lines().map { it.type })
        assertEquals("event=tracking.not_registered tid=t-1", lines()[0].msg)
    }

    @Test
    fun debuggableBuildLogsInfoAndDebug() {
        context.applicationInfo.flags = originalFlags or ApplicationInfo.FLAG_DEBUGGABLE
        SdkLog.init(context)

        SdkLog.debug("x.debug")
        SdkLog.info("tracking.event_sent", "count" to 2)

        assertEquals(listOf(Log.DEBUG, Log.INFO), lines().map { it.type })
        assertEquals("event=tracking.event_sent count=2", lines()[1].msg)
    }

    @Test
    fun sensitiveFieldsAreNeverWritten() {
        SdkLog.warn(
            "auth.failed",
            "status" to 401,
            "token" to "secret-token",
            "idToken" to "secret-id-token",
            "deviceToken" to "secret-device-token",
            "userId" to "alice",
            "title" to "Hello",
            "body" to "World",
        )
        val msg = lines().single().msg
        assertEquals("event=auth.failed status=401", msg)
        listOf("secret", "alice", "Hello", "World").forEach { assertFalse(msg.contains(it)) }
    }

    @Test
    fun nullValuesAreWrittenAsNull() {
        SdkLog.warn("rate_limit.exceeded", "retry_after" to null)
        assertTrue(lines().single().msg == "event=rate_limit.exceeded retry_after=null")
    }
}
