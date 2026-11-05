package io.reliablepush.android.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

private class PlainCipher : TokenCipher {
    var failEncrypt = false
    override fun encrypt(plain: ByteArray): ByteArray {
        if (failEncrypt) throw java.security.GeneralSecurityException("no key")
        return plain.copyOf()
    }
    override fun decrypt(blob: ByteArray) = blob.copyOf()
    override fun reset() {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class EventSenderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = TestBackend()
    private val cipher = PlainCipher()
    private lateinit var store: TokenStore
    private lateinit var queue: EventQueue
    private lateinit var sender: EventSender

    // token valid 0..100000 s; "now" = 50000 s, so no proactive refresh by default
    private var now = 50_000_000L
    private val freshJwt = fakeJwt(0, 100_000)

    @Before
    fun setUp() {
        context.getSharedPreferences("io.reliablepush.store", Context.MODE_PRIVATE).edit().clear().commit()
        RefreshGate.reset()
        ShadowLog.clear()
        store = TokenStore(context, cipher)
        store.save(Registration("dev-1", freshJwt, 100_000_000L))
        queue = EventQueue(tmp.newFolder())
        sender = newSender()
    }

    private fun newSender() = EventSender(
        DeviceApi(backend.baseUrl, "news", "reliable-push-android/test"), store, queue, { now },
    )

    @After
    fun tearDown() = backend.close()

    private fun fill(n: Int) = (1..n).forEach { queue.add(QueuedEvent("tid-$it", "DELIVERED", it.toLong())) }

    private fun sentIds(req: RecordedRequest): List<String> {
        val arr = JSONObject(req.body).getJSONArray("events")
        return List(arr.length()) { arr.getJSONObject(it).getString("trackingId") }
    }

    private fun items(ids: List<String>, outcome: (String) -> String = { "ACCEPTED" }) =
        """{"accepted":${ids.size},"ignored":0,"items":[${ids.joinToString(",") { """{"trackingId":"$it","outcome":"${outcome(it)}"}""" }}]}"""

    private fun echoAll(outcome: (String) -> String = { "ACCEPTED" }) {
        backend.handler = { req -> ScriptedResponse(200, items(sentIds(req), outcome)) }
    }

    private fun eventRequests() = backend.requests.filter { it.path == "/v1/events" }

    @Test
    fun flushRemovesAcceptedDuplicateLateNotFoundForbidden() {
        fill(5)
        val outcomes = mapOf(
            "tid-1" to "ACCEPTED", "tid-2" to "DUPLICATE", "tid-3" to "LATE", "tid-4" to "NOT_FOUND", "tid-5" to "FORBIDDEN",
        )
        echoAll { outcomes.getValue(it) }
        assertEquals(FlushResult.Done, sender.flush())
        assertEquals(0, queue.size())
        val req = eventRequests().single()
        assertEquals("Bearer $freshJwt", req.headers["authorization"])
        assertEquals(2, ShadowLog.getLogs().count { it.msg.contains("event=tracking.event_dropped") && it.type == android.util.Log.WARN })
        assertTrue(ShadowLog.getLogs().none { it.msg.contains(freshJwt) })
    }

    @Test
    fun flushKeepsEventsOn429And5xxAndNetworkError() {
        fill(3)
        backend.enqueue(ScriptedResponse(429, headers = mapOf("Retry-After" to "42")))
        assertEquals(FlushResult.RetryLater(42), sender.flush())
        backend.enqueue(ScriptedResponse(503, headers = mapOf("Retry-After" to "7")))
        assertEquals(FlushResult.RetryLater(7), sender.flush())
        backend.enqueue(ScriptedResponse(500))
        assertEquals(FlushResult.RetryLater(null), sender.flush())
        backend.enqueue(ScriptedResponse(408))
        assertEquals(FlushResult.RetryLater(null), sender.flush())
        backend.enqueue(ScriptedResponse(302, headers = mapOf("Location" to "http://evil.example/")))
        assertEquals(FlushResult.RetryLater(null), sender.flush())
        backend.enqueue(ScriptedResponse(202))
        assertEquals(FlushResult.RetryLater(null), sender.flush())
        backend.enqueue(ScriptedResponse(200, "not json")) // malformed 2xx -> NetworkError
        assertEquals(FlushResult.RetryLater(null), sender.flush())
        assertEquals(3, queue.size())
        // one request per flush: no tight loop inside a flush
        assertEquals(7, eventRequests().size)
        val deadUrl = TestBackend().use { it.baseUrl } // closed again: connection refused
        assertEquals(FlushResult.RetryLater(null), newSenderOn(deadUrl).flush())
        assertEquals(3, queue.size())
    }

    private fun newSenderOn(url: String) =
        EventSender(DeviceApi(url, "news", "reliable-push-android/test"), store, queue, { now })

    @Test
    fun flushDropsBatchOnOther4xx() {
        fill(3)
        backend.enqueue(ScriptedResponse(422, """{"title":"x","status":422}"""))
        assertEquals(FlushResult.Done, sender.flush())
        assertEquals(0, queue.size())
        assertTrue(ShadowLog.getLogs().any { it.msg == "event=tracking.event_dropped count=3 status=422" })
    }

    @Test
    fun flushBatchesFifty() {
        fill(120)
        echoAll()
        assertEquals(FlushResult.Done, sender.flush())
        assertEquals(listOf(50, 50, 20), eventRequests().map { sentIds(it).size })
        assertEquals(0, queue.size())
    }

    @Test
    fun noRegistrationMeansNotRegistered() {
        store.clear()
        fill(1)
        assertEquals(FlushResult.NotRegistered, sender.flush())
        assertEquals(0, backend.requests.size)
    }

    @Test
    fun emptyQueueIsDoneWithoutRequests() {
        assertEquals(FlushResult.Done, sender.flush())
        assertEquals(0, backend.requests.size)
    }

    @Test
    fun on401RefreshesOnceAndRetries() {
        fill(2)
        val newJwt = fakeJwt(0, 200_000)
        backend.handler = { req ->
            when {
                req.path == "/v1/devices/token:refresh" ->
                    ScriptedResponse(200, """{"deviceToken":"$newJwt","expiresAt":"1970-01-03T07:33:20Z"}""")
                req.headers["authorization"] == "Bearer $newJwt" -> ScriptedResponse(200, items(sentIds(req)))
                else -> ScriptedResponse(401)
            }
        }
        assertEquals(FlushResult.Done, sender.flush())
        assertEquals(listOf("/v1/events", "/v1/devices/token:refresh", "/v1/events"), backend.requests.map { it.path })
        assertEquals(newJwt, store.load()!!.token.raw)
        assertEquals("dev-1", store.load()!!.deviceId)
        val prefs = context.getSharedPreferences("io.reliablepush.store", Context.MODE_PRIVATE)
        assertEquals(200_000_000L, prefs.getLong("expires_at", -1))
    }

    private fun refreshFailsOn401Path(refresh: ScriptedResponse) {
        fill(2)
        backend.handler = { req -> if (req.path.endsWith("refresh")) refresh else ScriptedResponse(401) }
        assertEquals(FlushResult.RetryLater(null), sender.flush())
        assertEquals(2, queue.size())
        assertNotNull(store.load())
    }

    @Test
    fun refreshIoErrorOn401PathKeepsEvents() = refreshFailsOn401Path(ScriptedResponse(200, "garbage"))

    @Test
    fun refresh5xxOn401PathKeepsEvents() = refreshFailsOn401Path(ScriptedResponse(503))

    @Test
    fun earlyRefreshAnswered401ClearsRegistration() {
        fill(1)
        now = 95_000_000L
        backend.handler = { ScriptedResponse(401) }
        assertEquals(FlushResult.NotRegistered, sender.flush())
        assertNull(store.load())
        assertEquals(listOf("/v1/devices/token:refresh"), backend.requests.map { it.path })
    }

    @Test
    fun saveFailingAfterRefreshMeansNotRegistered() {
        fill(1)
        now = 95_000_000L
        val newJwt = fakeJwt(90_000, 190_000)
        backend.handler = { ScriptedResponse(200, """{"deviceToken":"$newJwt","expiresAt":"1970-01-03T04:46:40Z"}""") }
        cipher.failEncrypt = true
        assertEquals(FlushResult.NotRegistered, sender.flush())
        assertNull(store.load())
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun secondRefreshStillFailing401KeepsEventsWithoutLooping() {
        fill(1)
        val newJwt = fakeJwt(0, 200_000)
        backend.handler = { req ->
            if (req.path.endsWith("refresh")) ScriptedResponse(200, """{"deviceToken":"$newJwt","expiresAt":"1970-01-03T07:33:20Z"}""")
            else ScriptedResponse(401)
        }
        assertEquals(FlushResult.RetryLater(null), sender.flush())
        assertEquals(3, backend.requests.size)
        assertEquals(1, queue.size())
    }

    @Test
    fun refreshAnswered401ClearsRegistration() {
        fill(2)
        backend.handler = { req -> ScriptedResponse(401) }
        assertEquals(FlushResult.NotRegistered, sender.flush())
        assertNull(store.load())
        assertEquals(2, queue.size())
    }

    @Test
    fun secondRefreshInSameProcessIsNotAttempted() {
        fill(2)
        assertTrue(RefreshGate.tryAcquire()) // an earlier refresh in this process
        backend.handler = { ScriptedResponse(401) }
        assertEquals(FlushResult.RetryLater(null), sender.flush())
        assertEquals(listOf("/v1/events"), backend.requests.map { it.path })
        assertNotNull(store.load())
    }

    @Test
    fun refreshesFirstWhenTokenNearExpiry() {
        fill(1)
        now = 95_000_000L // 5 % left
        val newJwt = fakeJwt(90_000, 190_000)
        backend.handler = { req ->
            if (req.path.endsWith("refresh")) ScriptedResponse(200, """{"deviceToken":"$newJwt","expiresAt":"1970-01-03T04:46:40Z"}""")
            else ScriptedResponse(200, items(sentIds(req)))
        }
        assertEquals(FlushResult.Done, sender.flush())
        assertEquals(listOf("/v1/devices/token:refresh", "/v1/events"), backend.requests.map { it.path })
        assertEquals("Bearer $newJwt", backend.requests[1].headers["authorization"])
    }

    @Test
    fun failedEarlyRefreshStillTriesCurrentToken() {
        fill(1)
        now = 95_000_000L
        backend.handler = { req ->
            if (req.path.endsWith("refresh")) ScriptedResponse(503) else ScriptedResponse(200, items(sentIds(req)))
        }
        assertEquals(FlushResult.Done, sender.flush())
        assertEquals(0, queue.size())
    }

    @Test
    fun unmentionedEventIsKeptOnceThenDroppedWithWarn() {
        fill(2)
        backend.handler = { req -> ScriptedResponse(200, items(sentIds(req).filter { it == "tid-1" })) }
        assertEquals(FlushResult.RetryLater(null), sender.flush())
        assertEquals(listOf("tid-2"), queue.peek(10).map { it.trackingId })
        assertEquals(1, eventRequests().size) // no immediate resend inside the flush
        assertEquals(FlushResult.Done, sender.flush())
        assertEquals(0, queue.size())
        assertTrue(ShadowLog.getLogs().any { it.msg.contains("reason=unacknowledged") })
    }

    @Test
    fun missingItemsIsHandledLikeUnmentioned() {
        fill(1)
        backend.handler = { ScriptedResponse(200, """{"accepted":1,"ignored":0}""") } // no items
        assertEquals(FlushResult.RetryLater(null), sender.flush())
        assertEquals(1, queue.size())
        backend.handler = { ScriptedResponse(200, """{"accepted":1,"ignored":0,"items":[]}""") }
        assertEquals(FlushResult.Done, sender.flush()) // second miss drops it
        assertEquals(0, queue.size())
    }

    @Test
    fun stopsBetweenBatchesWhenAsked() {
        fill(120)
        echoAll()
        var calls = 0
        val s = EventSender(DeviceApi(backend.baseUrl, "news", "ua"), store, queue, { now }) { calls++ >= 1 }
        assertEquals(FlushResult.RetryLater(null), s.flush())
        assertEquals(1, eventRequests().size)
        assertEquals(70, queue.size())
    }
}
