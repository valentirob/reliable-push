package io.reliablepush.android.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private val nowSec = System.currentTimeMillis() / 1000
    private val freshJwt = fakeJwt(nowSec - 50_000, nowSec + 50_000)

    @Before
    fun setUp() {
        context.getSharedPreferences("io.reliablepush.store", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowLog.clear()
        store = TokenStore(context, cipher)
        store.save(Registration("dev-1", freshJwt, 0))
        queue = EventQueue(tmp.newFolder())
        sender = newSender()
    }

    private fun newSender() = EventSender(
        DeviceApi(backend.baseUrl, "news", "reliable-push-android/test"), store, queue,
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
        EventSender(DeviceApi(url, "news", "reliable-push-android/test"), store, queue)

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
    fun on401KeepsEventsSetsRenewalNeededAndNeverRefreshes() {
        fill(120)
        backend.handler = { ScriptedResponse(401) }
        assertFalse(store.renewalNeeded())
        assertEquals(FlushResult.NotRegistered, sender.flush())
        assertEquals(listOf("/v1/events"), backend.requests.map { it.path }) // one batch, no refresh, no further batches
        assertEquals(120, queue.size())
        assertTrue(store.renewalNeeded())
        assertNotNull(store.load()) // the registration stays: ensureRegistered renews it
        assertTrue(ShadowLog.getLogs().any { it.msg == "event=auth.failed status=401 call=events" && it.type == android.util.Log.WARN })
    }

    @Test
    fun tokenInItsLastTwentyPercentIsUsedAsIs() {
        fill(1)
        val nearExpiry = fakeJwt(nowSec - 95, nowSec + 5) // 5 % left: earlier versions refreshed here
        store.save(Registration("dev-1", nearExpiry, 0))
        echoAll()
        assertEquals(FlushResult.Done, sender.flush())
        assertEquals(listOf("/v1/events"), backend.requests.map { it.path })
        assertEquals("Bearer $nearExpiry", backend.requests.single().headers["authorization"])
    }

    @Test
    fun expiredTokenIsStillSentForTheBackendsGracePeriod() {
        fill(1)
        val expired = fakeJwt(nowSec - 100, nowSec - 10) // the backend accepts it on /v1/events for its grace period
        store.save(Registration("dev-1", expired, 0))
        echoAll()
        assertEquals(FlushResult.Done, sender.flush())
        assertEquals("Bearer $expired", backend.requests.single().headers["authorization"])
        assertFalse(store.renewalNeeded())
    }

    @Test
    fun noRefreshOnAnyStatus() {
        fill(1)
        for (status in listOf(401, 408, 429, 500, 503, 403)) {
            backend.enqueue(ScriptedResponse(status))
            sender.flush()
        }
        assertTrue(backend.requests.none { it.path.contains("refresh") })
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
        val s = EventSender(DeviceApi(backend.baseUrl, "news", "ua"), store, queue) { calls++ >= 1 }
        assertEquals(FlushResult.RetryLater(null), s.flush())
        assertEquals(1, eventRequests().size)
        assertEquals(70, queue.size())
    }
}
