package io.reliablepush.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.reliablepush.android.internal.EventQueue
import io.reliablepush.android.internal.FlushResult
import io.reliablepush.android.internal.RecordedRequest
import io.reliablepush.android.internal.RefreshGate
import io.reliablepush.android.internal.ScriptedResponse
import io.reliablepush.android.internal.Sdk
import io.reliablepush.android.internal.TestBackend
import io.reliablepush.android.internal.TestLaunchActivity
import io.reliablepush.android.internal.fakeJwt
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReliablePushTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val js = context.getSystemService(JobScheduler::class.java)
    private val jobId = 0x52500001
    private val backend = TestBackend()
    private val options = ReliablePushOptions(launchActivity = TestLaunchActivity::class.java)

    private val tid = "0b6f7c2e-8a7d-4a43-9d8e-6c1f0a2b3c4d"
    private val idToken = "id-token-SECRET"
    private val nowSec = System.currentTimeMillis() / 1000
    private val deviceJwt = fakeJwt(nowSec - 60, nowSec + 90L * 24 * 3600)

    // backend behaviour, switched per test
    @Volatile private var registerStatus = 201
    @Volatile private var eventsStatus = 200
    @Volatile private var eventsRetryAfter: String? = null
    @Volatile private var revokeStatus = 204
    @Volatile private var eventsGate: (() -> Unit)? = null
    private val jobPendingAtEventsCall = Collections.synchronizedList(ArrayList<Boolean>())

    @Before
    fun setUp() {
        ReliablePush.resetForTest()
        Sdk.cipherFactory = { FakeCipher() }
        context.getSharedPreferences("io.reliablepush.store", Context.MODE_PRIVATE).edit().clear().commit()
        EventQueue(Sdk.queueDir(context)).clear()
        js.cancelAll()
        RefreshGate.reset()
        ShadowLog.clear()
        backend.handler = ::answer
    }

    @After
    fun tearDown() {
        settle()
        ReliablePush.resetForTest()
        backend.close()
    }

    private fun answer(req: RecordedRequest): ScriptedResponse = when (req.method + " " + req.path) {
        "POST /v1/devices" ->
            if (registerStatus == 201) {
                ScriptedResponse(201, """{"deviceId":"dev-1","deviceToken":"$deviceJwt","expiresAt":"2099-01-01T00:00:00Z"}""")
            } else {
                ScriptedResponse(registerStatus)
            }
        "POST /v1/events" -> {
            eventsGate?.invoke()
            jobPendingAtEventsCall.add(js.getPendingJob(jobId) != null)
            if (eventsStatus == 200) {
                val arr = JSONObject(req.body).getJSONArray("events")
                val items = List(arr.length()) { """{"trackingId":"${arr.getJSONObject(it).getString("trackingId")}","outcome":"ACCEPTED"}""" }
                ScriptedResponse(200, """{"accepted":${items.size},"ignored":0,"items":[${items.joinToString(",")}]}""")
            } else {
                ScriptedResponse(eventsStatus, headers = eventsRetryAfter?.let { mapOf("Retry-After" to it) } ?: emptyMap())
            }
        }
        "DELETE /v1/devices/me" -> ScriptedResponse(revokeStatus)
        else -> ScriptedResponse(404)
    }

    private fun configure(appId: String = "news", url: String = backend.baseUrl) =
        ReliablePush.configure(context, url, appId, options)

    private fun registered(): RegisterResult {
        configure()
        var result: RegisterResult? = null
        ReliablePush.register(idToken) { result = it }
        settle()
        return result!!
    }

    private fun events(): List<Pair<String, String>> = backend.requests.filter { it.path == "/v1/events" }.flatMap { r ->
        val arr = JSONObject(r.body).getJSONArray("events")
        List(arr.length()) { arr.getJSONObject(it).getString("trackingId") to arr.getJSONObject(it).getString("type") }
    }

    private fun queueSize() = EventQueue(Sdk.queueDir(context)).size()

    private fun posted(id: String = tid): Notification? = shadowOf(nm).getNotification("rp", id.hashCode())

    private fun push(id: String? = tid, title: String? = "Hello", body: String? = "World"): Map<String, String> =
        buildMap {
            id?.let { put("rp_tid", it) }
            title?.let { put("title", it) }
            body?.let { put("body", it) }
        }

    private fun warnings() = ShadowLog.getLogsForTag("ReliablePush").filter { it.type == Log.WARN }.map { it.msg }

    // ---- handle ----

    @Test
    fun handleIgnoresMessageWithoutRpTid() {
        registered()
        assertFalse(ReliablePush.handle(context, mapOf("title" to "x", "body" to "y")))
        settle()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertEquals(0, queueSize())
        assertTrue(events().isEmpty())
    }

    @Test
    fun handleIgnoresMalformedRpTid() {
        registered()
        assertFalse(ReliablePush.handle(context, push(id = "not-a-uuid")))
        assertFalse(ReliablePush.handle(context, push(id = "$tid\nevent=forged")))
        settle()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertEquals(0, queueSize())
        assertEquals(2, warnings().count { it.startsWith("event=tracking.event_dropped") && it.contains("reason=malformed_rp_tid") })
        assertTrue(warnings().none { it.contains("not-a-uuid") || it.contains("forged") })
    }

    @Test
    fun handlePostsNotificationThenQueuesDelivered() {
        registered()
        assertTrue(ReliablePush.handle(context, push()))

        // visible before handle returns, tracking happens afterwards on the SDK executor
        val n = posted()!!
        assertEquals("Hello", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("World", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(1, shadowOf(nm).allNotifications.size)

        settle()
        assertEquals(listOf(tid to "DELIVERED"), events())
        assertEquals("Bearer $deviceJwt", backend.requests.single { it.path == "/v1/events" }.headers["authorization"])
        assertEquals(listOf(true), jobPendingAtEventsCall) // job scheduled before the flush
        assertNull(js.getPendingJob(jobId)) // and cancelled once the queue is empty
        assertEquals(0, queueSize())
    }

    @Test
    fun failedFlushKeepsEventAndJob() {
        registered()
        eventsStatus = 503
        assertTrue(ReliablePush.handle(context, push()))
        settle()
        assertEquals(1, queueSize())
        assertEquals(30_000L, js.getPendingJob(jobId)!!.minLatencyMillis)

        eventsStatus = 429
        eventsRetryAfter = "120"
        ReliablePush.handle(context, push(id = "1c7a8d3f-9b8e-4b54-8e9f-7d2a1b3c4d5e"))
        settle()
        assertEquals(2, queueSize())
        assertEquals(120_000L, js.getPendingJob(jobId)!!.minLatencyMillis)
    }

    @Test
    fun notificationsDisabledMeansNoDelivered() {
        registered()
        shadowOf(nm).setNotificationsEnabled(false)
        assertFalse(ReliablePush.handle(context, push()))
        settle()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertEquals(0, queueSize())
        assertTrue(events().isEmpty())
        assertNull(js.getPendingJob(jobId))
    }

    @Test
    fun blockedChannelMeansNoDelivered() {
        registered()
        nm.createNotificationChannel(NotificationChannel("reliablepush_default", "Off", NotificationManager.IMPORTANCE_NONE))
        assertFalse(ReliablePush.handle(context, push()))
        settle()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertEquals(0, queueSize())
        assertTrue(events().isEmpty())
    }

    @Test
    fun missingTitleUsesAppLabel() {
        registered()
        assertTrue(ReliablePush.handle(context, push(title = null, body = null)))
        val n = posted()!!
        assertEquals(context.applicationInfo.loadLabel(context.packageManager).toString(), n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
    }

    @Test
    fun fallbackTitleAndBodyAreUsedWhenDataHasNone() {
        registered()
        assertTrue(ReliablePush.handle(context, push(title = null, body = null), "Fallback title", "Fallback body"))
        val n = posted()!!
        assertEquals("Fallback title", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Fallback body", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun notRegisteredPostsButDoesNotQueue() {
        configure()
        assertTrue(ReliablePush.handle(context, push()))
        assertNotNull(posted())
        settle()
        assertEquals(0, queueSize())
        assertTrue(backend.requests.isEmpty())
        assertTrue(warnings().any { it.startsWith("event=tracking.not_registered") && it.contains("tid=$tid") })
    }

    @Test
    fun concurrentHandleCallsQueueEveryEvent() {
        registered()
        eventsStatus = 503 // keep everything queued
        val ids = List(20) { java.util.UUID.randomUUID().toString() }
        val start = CountDownLatch(1)
        val threads = ids.map { id -> Thread { start.await(); ReliablePush.handle(context, push(id = id)) }.apply { start() } }
        start.countDown()
        threads.forEach { it.join(10_000) }
        assertEquals(20, queueSize()) // persisted by the handle calls themselves, not by the SDK thread
        settle()
        assertEquals(20, queueSize())
        eventsStatus = 200
        ReliablePush.register(idToken) // a successful register flushes the queue
        settle()
        assertEquals(ids.toSet(), events().filter { it.second == "DELIVERED" }.map { it.first }.toSet())
        assertEquals(0, queueSize())
    }

    // ---- trackOpen ----

    @Test
    fun trackOpenSendsReadOnceAndConsumesExtra() {
        registered()
        ReliablePush.handle(context, push())
        settle()
        val tap: Intent = shadowOf(posted()!!.contentIntent).savedIntent

        ReliablePush.trackOpen(tap)
        assertFalse(tap.hasExtra("rp_tid"))
        settle()
        ReliablePush.trackOpen(tap) // recreated activity sees the same intent
        settle()

        assertEquals(listOf(tid to "DELIVERED", tid to "READ"), events())
    }

    @Test
    fun trackOpenIgnoresMissingOrMalformedId() {
        registered()
        ReliablePush.trackOpen(null)
        ReliablePush.trackOpen(Intent())
        val bad = Intent().putExtra("rp_tid", "nope")
        ReliablePush.trackOpen(bad)
        settle()
        assertTrue(events().isEmpty())
        assertEquals(0, queueSize())
    }

    // ---- register ----

    @Test
    fun registerStoresTokenAndCallsBackOnMainThread() {
        configure()
        val calls = ArrayList<Pair<RegisterResult, Boolean>>()
        ReliablePush.register(idToken) { calls.add(it to (Looper.myLooper() == Looper.getMainLooper())) }
        Sdk.awaitIdle()
        assertTrue("callback must be posted to the main thread", calls.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf<Pair<RegisterResult, Boolean>>(RegisterResult.Success("dev-1") to true), calls)
        assertEquals("dev-1", ReliablePush.deviceId)
        val req = JSONObject(backend.requests.single().body)
        assertEquals("news", req.getString("appId"))
        assertEquals(idToken, req.getString("idToken"))
        assertEquals("ANDROID", req.getString("platform"))
        assertFalse(req.has("deviceId"))
        assertEquals("reliable-push-android/${ReliablePush.VERSION}", backend.requests.single().headers["user-agent"])
        // the token is stored encrypted, never in plain text
        val prefs = context.getSharedPreferences("io.reliablepush.store", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(prefs.contains(deviceJwt))
    }

    @Test
    fun reRegisterSendsTheStoredDeviceId() {
        registered()
        ReliablePush.register(idToken)
        settle()
        assertEquals("dev-1", JSONObject(backend.requests.last().body).getString("deviceId"))
    }

    @Test
    fun registerFailureReportsHttpStatusOrNull() {
        configure()
        registerStatus = 401
        var result: RegisterResult? = null
        ReliablePush.register(idToken) { result = it }
        settle()
        assertEquals(RegisterResult.Failed(401), result)
        assertNull(ReliablePush.deviceId)
        assertTrue(warnings().any { it.startsWith("event=auth.failed") })

        registerStatus = ScriptedResponse.DROP // connection closed without an answer
        ReliablePush.register(idToken) { result = it }
        settle()
        assertEquals(RegisterResult.Failed(null), result)
    }

    @Test
    fun registerBeforeConfigureThrows() {
        assertThrows(IllegalStateException::class.java) { ReliablePush.register(idToken) }
    }

    @Test
    fun logsNeverContainTokensOrContent() {
        registered()
        ReliablePush.handle(context, push(title = "Secret title", body = "Secret body"))
        settle()
        val all = ShadowLog.getLogs().joinToString("\n") { it.msg }
        listOf(idToken, deviceJwt, "Secret title", "Secret body").forEach { assertFalse(it, all.contains(it)) }
    }

    // ---- logout ----

    @Test
    fun logoutRevokesAndClearsQueueAndJob() {
        registered()
        eventsStatus = 503
        ReliablePush.handle(context, push())
        settle()
        assertEquals(1, queueSize())
        assertNotNull(js.getPendingJob(jobId))

        val calls = ArrayList<Pair<Boolean, Boolean>>()
        ReliablePush.logout { calls.add(it to (Looper.myLooper() == Looper.getMainLooper())) }
        settle()

        assertEquals(listOf(true to true), calls)
        val revoke = backend.requests.single { it.method == "DELETE" }
        assertEquals("/v1/devices/me", revoke.path)
        assertEquals("Bearer $deviceJwt", revoke.headers["authorization"])
        assertEquals(0, queueSize())
        assertNull(js.getPendingJob(jobId))
        assertNull(ReliablePush.deviceId)
    }

    @Test
    fun logoutClearsLocallyEvenWhenRevokeFails() {
        registered()
        revokeStatus = 500
        var result: Boolean? = null
        ReliablePush.logout { result = it }
        settle()
        assertEquals(false, result)
        assertNull(ReliablePush.deviceId)
    }

    // ---- configure ----

    @Test
    fun configureRejectsCleartextRemoteUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            ReliablePush.configure(context, "http://push.example.com", "news")
        }
        assertThrows(IllegalArgumentException::class.java) { ReliablePush.configure(context, "https://push.example.com", " ") }
        ReliablePush.configure(context, "https://push.example.com", "news") // https is fine
        ReliablePush.configure(context, "http://10.0.2.2:8080", "news") // emulator alias of the host
    }

    @Test
    fun configureWithSameConfigIsNoOp() {
        registered()
        eventsStatus = 503
        ReliablePush.handle(context, push())
        settle()
        val calls = backend.requests.size

        // same baseUrl + appId: no reconfiguration (which would flush the queued event again), new options apply
        ReliablePush.configure(context, backend.baseUrl, "news", options.copy(channelId = "other_channel"))
        settle()
        assertEquals(calls, backend.requests.size)
        assertEquals("dev-1", ReliablePush.deviceId)
        assertEquals(1, queueSize())

        val second = "1c7a8d3f-9b8e-4b54-8e9f-7d2a1b3c4d5e"
        assertTrue(ReliablePush.handle(context, push(id = second)))
        assertEquals("other_channel", posted(second)!!.channelId)
    }

    @Test
    fun changingTheJobIdCancelsTheOldJob() {
        registered()
        eventsStatus = 503
        ReliablePush.handle(context, push())
        settle()
        assertNotNull(js.getPendingJob(jobId))

        ReliablePush.configure(context, backend.baseUrl, "news", options.copy(jobId = jobId + 1))
        settle()
        assertNull(js.getPendingJob(jobId))
        assertNotNull(js.getPendingJob(jobId + 1)) // the queued event keeps a retry under the new id
        assertEquals("dev-1", ReliablePush.deviceId)
        assertEquals(1, queueSize())
    }

    // ---- durability: DELIVERED / READ are on disk before handle / trackOpen return ----

    /** Holds the SDK thread inside a flush (an HTTP call the backend does not answer yet) until the returned latch opens. */
    private fun blockSdkThreadInFlush(): CountDownLatch {
        val inFlush = CountDownLatch(1)
        val release = CountDownLatch(1)
        eventsGate = { inFlush.countDown(); release.await(8, TimeUnit.SECONDS) }
        Thread { ReliablePush.handle(context, push(id = "2d8b9e4a-0c9f-4c65-9fa0-8e3b2c4d5e6f")) }.apply { start(); join() }
        assertTrue("SDK thread did not reach the flush", inFlush.await(5, TimeUnit.SECONDS))
        return release
    }

    private fun queued(): List<Pair<String, String>> =
        EventQueue(Sdk.queueDir(context)).peek(100).map { it.trackingId to it.type } // a fresh instance reads the file

    @Test
    fun deliveredIsPersistedBeforeHandleReturns() {
        registered()
        val release = blockSdkThreadInFlush()
        try {
            var returned: Boolean? = null
            Thread { returned = ReliablePush.handle(context, push()) }.apply { start(); join() } // FCM worker thread
            assertEquals(true, returned)
            assertTrue(queued().contains(tid to "DELIVERED"))
            assertNotNull(js.getPendingJob(jobId))
        } finally {
            release.countDown()
            eventsGate = null
        }
        settle()
        assertTrue(events().contains(tid to "DELIVERED"))
    }

    @Test
    fun openIsPersistedWithoutWaitingForTheSdkThread() {
        registered()
        val release = blockSdkThreadInFlush()
        try {
            ReliablePush.trackOpen(Intent().putExtra("rp_tid", tid)) // main thread: persisted off the main thread
            Sdk.awaitPersisted()
            assertTrue(queued().contains(tid to "READ"))
            assertNotNull(js.getPendingJob(jobId))

            val other = "3e9c0f5b-1d0a-4d76-8ab1-9f4c3d5e6f70"
            Thread { ReliablePush.trackOpen(Intent().putExtra("rp_tid", other)) }.apply { start(); join() }
            assertTrue(queued().contains(other to "READ")) // any other thread: persisted before returning
        } finally {
            release.countDown()
            eventsGate = null
        }
        settle()
        assertTrue(events().contains(tid to "READ"))
    }

    @Test
    fun handleBeforeConfigureUsesTheStoredConfig() {
        registered()
        ReliablePush.resetForTest() // a new process whose host has not called configure (yet)
        assertTrue(ReliablePush.handle(context, push()))
        settle()
        assertTrue(events().contains(tid to "DELIVERED"))

        ReliablePush.trackOpen(Intent().putExtra("rp_tid", tid)) // no context: uses what handle loaded
        settle()
        assertTrue(events().contains(tid to "READ"))
    }

    @Test
    fun configureWithAnotherAppClearsRegistrationQueueAndJob() {
        registered()
        eventsStatus = 503
        ReliablePush.handle(context, push())
        settle()
        assertEquals(1, queueSize())

        configure(appId = "shop")
        settle()
        assertNull(ReliablePush.deviceId)
        assertEquals(0, queueSize())
        assertNull(js.getPendingJob(jobId))
        assertEquals(backend.baseUrl to "shop", Sdk.fromStoredConfig(context, jobId)!!.let { it.baseUrl to it.appId })
    }

    @Test
    fun configureAfterProcessRestartKeepsRegistration() {
        registered()
        ReliablePush.resetForTest() // new process: nothing in memory, same storage
        configure()
        settle()
        assertEquals("dev-1", ReliablePush.deviceId)
    }

    // ---- retry job composition ----

    @Test
    fun retryJobComposesFromStoredConfig() {
        assertNull(Sdk.fromStoredConfig(context, jobId))
        registered()
        eventsStatus = 503
        ReliablePush.handle(context, push())
        settle()
        eventsStatus = 200

        val job = Sdk.fromStoredConfig(context, jobId)!!
        assertEquals(FlushResult.Done, job.sender { false }.flush())
        assertEquals(0, queueSize())
        assertEquals(listOf(tid to "DELIVERED"), events().filter { it.second == "DELIVERED" }.distinct())
    }

    @Test
    fun deviceIdIsNullBeforeConfigure() = assertNull(ReliablePush.deviceId)

    @Test
    fun handleBeforeConfigureStillShowsTheNotification() {
        assertTrue(ReliablePush.handle(context, push()))
        assertNotNull(posted())
        settle()
        assertTrue(backend.requests.isEmpty())
        assertTrue(warnings().any { it.startsWith("event=tracking.not_registered") })
    }
}
