package io.reliablepush.android.internal

import android.util.Log
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DeviceApiTest {
    private val backend = TestBackend()
    private val ua = "reliable-push-android/test"
    private val expires = "2026-11-05T10:15:30Z"
    private val expiresMillis = Instant.parse(expires).toEpochMilli()

    @After
    fun tearDown() = backend.close()

    private fun api(timeoutMs: Int = 10_000, url: String = backend.baseUrl) = DeviceApi(url, "news", ua, timeoutMs)

    private fun json(s: String) = ScriptedResponse(200, s, mapOf("Content-Type" to "application/json"))

    @Test
    fun registerSendsAppIdIdTokenPlatformAndParsesToken() {
        backend.enqueue(
            ScriptedResponse(201, """{"deviceId":"d-1","deviceToken":"dev-tok","expiresAt":"$expires"}""",
                mapOf("Content-Type" to "application/json")),
        )
        val r = api().register("the-id-token", null)
        assertEquals(ApiResult.Ok(Registration("d-1", "dev-tok", expiresMillis)), r)
        val req = backend.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/v1/devices", req.path)
        assertEquals("application/json", req.headers["content-type"])
        assertEquals("application/json, application/problem+json", req.headers["accept"])
        assertEquals(ua, req.headers["user-agent"])
        assertFalse(req.headers.containsKey("authorization"))
        val body = JSONObject(req.body)
        assertEquals("news", body.getString("appId"))
        assertEquals("the-id-token", body.getString("idToken"))
        assertEquals("ANDROID", body.getString("platform"))
        assertFalse(body.has("deviceId"))
    }

    @Test
    fun registerReusesStoredDeviceId() {
        backend.enqueue(ScriptedResponse(201, """{"deviceId":"d-2","deviceToken":"t","expiresAt":"$expires"}"""))
        api().register("idt", "11111111-1111-1111-1111-111111111111")
        assertEquals("11111111-1111-1111-1111-111111111111", JSONObject(backend.requests.single().body).getString("deviceId"))
    }

    @Test
    fun refreshSendsBearerToken() {
        backend.enqueue(json("""{"deviceToken":"new-tok","expiresAt":"2026-11-05T12:15:30+02:00"}"""))
        val r = api().refresh("old-tok")
        assertEquals(ApiResult.Ok(IssuedToken("new-tok", expiresMillis)), r)
        val req = backend.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/v1/devices/token:refresh", req.path)
        assertEquals("Bearer old-tok", req.headers["authorization"])
    }

    @Test
    fun revokeExpects204() {
        backend.enqueue(ScriptedResponse(204), ScriptedResponse(401), ScriptedResponse(200))
        assertEquals(ApiResult.Ok(Unit), api().revoke("tok"))
        val req = backend.requests.single()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/devices/me", req.path)
        assertEquals("Bearer tok", req.headers["authorization"])
        assertEquals(ApiResult.Http(401, null), api().revoke("tok"))
        // 200 is not the documented success status
        assertEquals(ApiResult.Http(200, null), api().revoke("tok"))
    }

    @Test
    fun sendEventsParsesPerItemOutcomes() {
        backend.enqueue(
            json("""{"accepted":1,"ignored":1,"items":[{"trackingId":"a","outcome":"ACCEPTED"},{"trackingId":"b","outcome":"LATE"}]}"""),
        )
        val events = listOf(QueuedEvent("a", "DELIVERED", 1_000L), QueuedEvent("b", "READ", expiresMillis))
        val r = api().sendEvents("tok", events)
        assertEquals(ApiResult.Ok(listOf(EventOutcome("a", "ACCEPTED"), EventOutcome("b", "LATE"))), r)
        val req = backend.requests.single()
        assertEquals("/v1/events", req.path)
        assertEquals("Bearer tok", req.headers["authorization"])
        val sent = JSONObject(req.body).getJSONArray("events")
        assertEquals(2, sent.length())
        assertEquals("a", sent.getJSONObject(0).getString("trackingId"))
        assertEquals("DELIVERED", sent.getJSONObject(0).getString("type"))
        assertEquals("1970-01-01T00:00:01Z", sent.getJSONObject(0).getString("occurredAt"))
        assertEquals(expires, sent.getJSONObject(1).getString("occurredAt"))
    }

    @Test
    fun retryAfterIsParsedOn429() {
        backend.enqueue(
            ScriptedResponse(429, """{"title":"Too many","status":429}""",
                mapOf("Content-Type" to "application/problem+json", "Retry-After" to "17")),
            ScriptedResponse(503, "", mapOf("Retry-After" to "soon")),
        )
        assertEquals(ApiResult.Http(429, 17L), api().sendEvents("t", listOf(QueuedEvent("a", "READ", 0))))
        assertEquals(ApiResult.Http(503, null), api().refresh("t"))
    }

    @Test
    fun serverErrorAndTimeoutMapToHttpAndNetworkError() {
        backend.enqueue(ScriptedResponse(500, "boom"), ScriptedResponse(200, "{}", delayMs = 1_500))
        assertEquals(ApiResult.Http(500, null), api().refresh("t"))
        assertEquals(ApiResult.NetworkError, api(timeoutMs = 300).refresh("t"))
        // nothing listening
        val dead = TestBackend().also { it.close() }
        assertEquals(ApiResult.NetworkError, api(url = dead.baseUrl).refresh("t"))
    }

    @Test
    fun malformedSuccessBodyIsNetworkError() {
        backend.enqueue(json("not json"), json("""{"deviceToken":"x"}"""))
        assertEquals(ApiResult.NetworkError, api().refresh("t"))
        assertEquals(ApiResult.NetworkError, api().refresh("t"))
    }

    @Test
    fun doesNotFollowRedirects() {
        backend.handler = { req ->
            if (req.path == "/v1/devices/token:refresh") {
                ScriptedResponse(302, "", mapOf("Location" to "/elsewhere"))
            } else {
                ScriptedResponse(200, "{}")
            }
        }
        assertEquals(ApiResult.Http(302, null), api().refresh("secret-tok"))
        assertEquals(1, backend.requests.size)
        assertTrue(backend.requests.none { it.path == "/elsewhere" })
    }

    @Test
    fun neverLogsTokens() {
        backend.enqueue(
            ScriptedResponse(201, """{"deviceId":"d","deviceToken":"SECRET-DEVICE-TOKEN","expiresAt":"$expires"}"""),
            ScriptedResponse(500, "SECRET-ID-TOKEN"),
            ScriptedResponse(200, "garbage SECRET-DEVICE-TOKEN"),
        )
        api().register("SECRET-ID-TOKEN", null)
        api().refresh("SECRET-DEVICE-TOKEN")
        api().sendEvents("SECRET-DEVICE-TOKEN", listOf(QueuedEvent("a", "READ", 0)))
        api(timeoutMs = 100, url = "http://127.0.0.1:1").revoke("SECRET-DEVICE-TOKEN")
        Log.i("probe", "log capture works")
        val lines = ShadowLog.getLogs()
        assertTrue(lines.any { it.tag == "probe" })
        assertTrue(lines.none { "SECRET" in it.msg || it.throwable?.toString()?.contains("SECRET") == true })
    }
}
