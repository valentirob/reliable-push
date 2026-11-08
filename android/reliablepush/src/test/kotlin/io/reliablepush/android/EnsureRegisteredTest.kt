package io.reliablepush.android

import android.app.job.JobScheduler
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.reliablepush.android.internal.EventQueue
import io.reliablepush.android.internal.QueuedEvent
import io.reliablepush.android.internal.RecordedRequest
import io.reliablepush.android.internal.Registration
import io.reliablepush.android.internal.ScriptedResponse
import io.reliablepush.android.internal.Sdk
import io.reliablepush.android.internal.TestBackend
import io.reliablepush.android.internal.TokenStore
import io.reliablepush.android.internal.fakeJwt
import org.json.JSONObject
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
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** `ReliablePush.ensureRegistered`: the decision order of spec §3.2, one run at a time, never throws. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class EnsureRegisteredTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val js = context.getSystemService(JobScheduler::class.java)
    private val backend = TestBackend()
    private val nowSec = System.currentTimeMillis() / 1000
    private val tid = "0b6f7c2e-8a7d-4a43-9d8e-6c1f0a2b3c4d"

    /** 90 days, just issued: nowhere near the last 20 %. */
    private val freshJwt = fakeJwt(nowSec - 60, nowSec + 90L * 24 * 3600)

    /** Issued by `register`. */
    private val registeredJwt = fakeJwt(nowSec - 1, nowSec + 90L * 24 * 3600)

    /** Issued by `token:refresh`. */
    private val refreshedJwt = fakeJwt(nowSec, nowSec + 90L * 24 * 3600 + 7)

    @Volatile private var refreshStatus = 200
    @Volatile private var registerStatus = 201
    private val providerCalls = AtomicInteger()

    @Before
    fun setUp() {
        ReliablePush.resetForTest()
        Sdk.cipherFactory = { FakeCipher() }
        context.getSharedPreferences("io.reliablepush.store", Context.MODE_PRIVATE).edit().clear().commit()
        EventQueue(Sdk.queueDir(context)).clear()
        js.cancelAll()
        backend.handler = ::answer
        ReliablePush.configure(context, backend.baseUrl, "news")
        settle()
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
                ScriptedResponse(201, """{"deviceId":"dev-2","deviceToken":"$registeredJwt","expiresAt":"2099-01-01T00:00:00Z"}""")
            } else {
                ScriptedResponse(registerStatus)
            }
        "POST /v1/devices/token:refresh" ->
            if (refreshStatus == 200) {
                ScriptedResponse(200, """{"deviceToken":"$refreshedJwt","expiresAt":"2099-01-01T00:00:00Z"}""")
            } else {
                ScriptedResponse(refreshStatus)
            }
        "POST /v1/events" ->
            if (req.headers["authorization"] == "Bearer $refreshedJwt" || req.headers["authorization"] == "Bearer $registeredJwt") {
                val arr = JSONObject(req.body).getJSONArray("events")
                val items = List(arr.length()) { """{"trackingId":"${arr.getJSONObject(it).getString("trackingId")}","outcome":"ACCEPTED"}""" }
                ScriptedResponse(200, """{"accepted":${items.size},"ignored":0,"items":[${items.joinToString(",")}]}""")
            } else {
                ScriptedResponse(401)
            }
        else -> ScriptedResponse(404)
    }

    private fun store() = TokenStore(context, FakeCipher())

    private fun storeToken(jwt: String) = store().save(Registration("dev-1", jwt, 0))

    private fun paths() = backend.requests.map { it.path }

    private fun provider(token: String?): (deliver: (String?) -> Unit) -> Unit = { deliver ->
        providerCalls.incrementAndGet()
        deliver(token)
    }

    private fun ensure(provider: (deliver: (String?) -> Unit) -> Unit): RegisterResult? {
        var result: RegisterResult? = null
        ReliablePush.ensureRegistered(provider) { result = it }
        settle()
        return result
    }

    @Test
    fun noTokenCallsTheProviderAndRegisters() {
        assertEquals(RegisterResult.Success("dev-2"), ensure(provider("id-token")))
        assertEquals(1, providerCalls.get())
        assertEquals(listOf("/v1/devices"), paths())
        assertEquals("id-token", JSONObject(backend.requests.single().body).getString("idToken"))
        assertEquals(registeredJwt, store().load()!!.token.raw)
    }

    @Test
    fun providerWithoutTokenIsNotLoggedInAndSendsNothing() {
        assertEquals(RegisterResult.NotLoggedIn, ensure(provider(null)))
        assertEquals(RegisterResult.NotLoggedIn, ensure(provider("")))
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun throwingProviderIsNotLoggedIn() {
        assertEquals(RegisterResult.NotLoggedIn, ensure { throw IllegalStateException("host bug") })
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun validTokenIsAlreadyRegisteredWithoutNetworkOrProvider() {
        storeToken(freshJwt)
        assertEquals(RegisterResult.AlreadyRegistered, ensure(provider("id-token")))
        assertEquals(0, providerCalls.get())
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun lastTwentyPercentRefreshes() {
        storeToken(fakeJwt(nowSec - 85, nowSec + 15)) // 15 % left
        assertEquals(RegisterResult.Success("dev-1"), ensure(provider("id-token")))
        assertEquals(0, providerCalls.get())
        assertEquals(listOf("/v1/devices/token:refresh"), paths())
        assertEquals(refreshedJwt, store().load()!!.token.raw)
        assertEquals("dev-1", store().load()!!.deviceId)
    }

    @Test
    fun renewalNeededRefreshesThenClearsTheFlagAndFlushes() {
        storeToken(freshJwt)
        // a 401 on the events endpoint (the receive path) sets renewalNeeded and keeps the event
        Sdk.create(context, backend.baseUrl, "news", 0x52500001).apply {
            queue.add(QueuedEvent(tid, "DELIVERED", 1L))
            flush()
        }
        assertTrue(store().renewalNeeded())
        assertEquals(1, EventQueue(Sdk.queueDir(context)).size())
        backend.requests.clear()

        assertEquals(RegisterResult.Success("dev-1"), ensure(provider("id-token")))
        assertEquals(listOf("/v1/devices/token:refresh", "/v1/events"), paths())
        assertEquals("Bearer $refreshedJwt", backend.requests[1].headers["authorization"])
        assertFalse(store().renewalNeeded())
        assertEquals(0, EventQueue(Sdk.queueDir(context)).size())
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun refreshAnswered401TakesTheIdTokenPath() {
        storeToken(fakeJwt(nowSec - 85, nowSec + 15))
        refreshStatus = 401
        assertEquals(RegisterResult.Success("dev-2"), ensure(provider("id-token")))
        assertEquals(listOf("/v1/devices/token:refresh", "/v1/devices"), paths())
        assertEquals(1, providerCalls.get())
        // the known device id is sent, so the backend keeps the same installation
        assertEquals("dev-1", JSONObject(backend.requests[1].body).getString("deviceId"))
    }

    @Test
    fun refreshFailingOtherwiseReportsTheStatus() {
        storeToken(fakeJwt(nowSec - 85, nowSec + 15))
        refreshStatus = 503
        assertEquals(RegisterResult.Failed(503), ensure(provider("id-token")))
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun expiredTokenTakesTheIdTokenPathWithoutRefresh() {
        storeToken(fakeJwt(nowSec - 100, nowSec - 10))
        assertEquals(RegisterResult.Success("dev-2"), ensure(provider("id-token")))
        assertEquals(listOf("/v1/devices"), paths())
    }

    @Test
    fun registerFailureIsReported() {
        registerStatus = 401
        assertEquals(RegisterResult.Failed(401), ensure(provider("bad-id-token")))
    }

    @Test
    fun providerThatNeverDeliversEndsNotLoggedInAfter30Seconds() {
        var late: ((String?) -> Unit)? = null
        var result: RegisterResult? = null
        ReliablePush.ensureRegistered({ deliver -> late = deliver }) { result = it }
        settle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
        settle()
        assertEquals(null, result)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        settle()
        assertEquals(RegisterResult.NotLoggedIn, result)

        late!!("too-late") // ignored
        settle()
        assertTrue(backend.requests.isEmpty())
        assertEquals(RegisterResult.NotLoggedIn, result)
    }

    @Test
    fun deliverIsUsedOnceFromAnyThread() {
        var result: RegisterResult? = null
        var calls = 0
        ReliablePush.ensureRegistered({ deliver ->
            Thread { deliver("id-token"); deliver("second") }.apply { start(); join() }
        }) { calls++; result = it }
        settle()
        assertEquals(RegisterResult.Success("dev-2"), result)
        assertEquals(1, calls)
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun concurrentCallsJoinOneRun() {
        var pending: ((String?) -> Unit)? = null
        val results = ArrayList<RegisterResult>()
        val provider: (deliver: (String?) -> Unit) -> Unit = { deliver -> providerCalls.incrementAndGet(); pending = deliver }
        ReliablePush.ensureRegistered(provider) { results.add(it) }
        ReliablePush.ensureRegistered(provider) { results.add(it) }
        settle()
        assertEquals(1, providerCalls.get())
        assertTrue(results.isEmpty())

        pending!!("id-token")
        settle()
        assertEquals(listOf<RegisterResult>(RegisterResult.Success("dev-2"), RegisterResult.Success("dev-2")), results)
        assertEquals(listOf("/v1/devices"), paths())

        // the run is over: the next call starts a new one (and finds a valid token)
        ReliablePush.ensureRegistered(provider) { results.add(it) }
        settle()
        assertEquals(RegisterResult.AlreadyRegistered, results.last())
    }

    @Test
    fun logoutEndsTheRunInProgress() {
        var pending: ((String?) -> Unit)? = null
        val results = ArrayList<RegisterResult>()
        val provider: (deliver: (String?) -> Unit) -> Unit = { deliver -> pending = deliver }
        ReliablePush.ensureRegistered(provider) { results.add(it) }
        ReliablePush.ensureRegistered(provider) { results.add(it) } // joined
        settle()
        var loggedOut: Boolean? = null
        ReliablePush.logout { loggedOut = it }
        settle()
        assertEquals(listOf<RegisterResult>(RegisterResult.NotLoggedIn, RegisterResult.NotLoggedIn), results)
        assertEquals(true, loggedOut)

        pending!!("id-token") // the old user's token, too late: ignored
        settle()
        assertTrue(backend.requests.none { it.path == "/v1/devices" })
        assertEquals(null, ReliablePush.deviceId)
        assertEquals(null, store().load())

        // a new run starts normally after the logout
        assertEquals(RegisterResult.Success("dev-2"), ensure(provider("new-user-token")))
    }

    @Test
    fun returnsAtOnceAndCallsBackOnTheMainThread() {
        val seen = ArrayList<Boolean>()
        ReliablePush.ensureRegistered(provider("id-token")) { seen.add(Looper.myLooper() == Looper.getMainLooper()) }
        assertTrue("work is asynchronous", seen.isEmpty())
        settle()
        assertEquals(listOf(true), seen)
    }

    @Test
    fun notConfiguredFailsWithoutThrowing() {
        ReliablePush.resetForTest()
        var result: RegisterResult? = null
        ReliablePush.ensureRegistered(provider("id-token")) { result = it }
        settle()
        assertEquals(RegisterResult.Failed(null), result)
        assertEquals(0, providerCalls.get())
    }
}
