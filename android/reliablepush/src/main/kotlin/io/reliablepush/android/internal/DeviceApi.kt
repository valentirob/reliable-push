package io.reliablepush.android.internal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal data class Registration(val deviceId: String, val deviceToken: String, val expiresAt: Long)

internal data class IssuedToken(val deviceToken: String, val expiresAt: Long)

/** [type] is `DELIVERED` or `READ`. */
internal data class QueuedEvent(val trackingId: String, val type: String, val occurredAtMillis: Long)

internal data class EventOutcome(val trackingId: String, val outcome: String)

/** Client of the backend's device API (`/v1/devices`, `/v1/events`). Never logs tokens or bodies. */
internal class DeviceApi(
    baseUrl: String,
    private val appId: String,
    userAgent: String,
    timeoutMs: Int = HttpTransport.DEFAULT_TIMEOUT_MS,
) {
    private val base = BaseUrl.validate(baseUrl)
    private val transport = HttpTransport(userAgent, timeoutMs)

    fun register(idToken: String, deviceId: String?): ApiResult<Registration> {
        val body = JSONObject().put("appId", appId).put("idToken", idToken).put("platform", "ANDROID")
        if (deviceId != null) body.put("deviceId", deviceId)
        return call("POST", "/v1/devices", null, body.toString(), 201) {
            Registration(it.getString("deviceId"), it.getString("deviceToken"), parseInstant(it.getString("expiresAt")))
        }
    }

    fun refresh(token: String): ApiResult<IssuedToken> =
        call("POST", "/v1/devices/token:refresh", token, null, 200) {
            IssuedToken(it.getString("deviceToken"), parseInstant(it.getString("expiresAt")))
        }

    fun revoke(token: String): ApiResult<Unit> = call("DELETE", "/v1/devices/me", token, null, 204) { Unit }

    fun sendEvents(token: String, events: List<QueuedEvent>): ApiResult<List<EventOutcome>> {
        val arr = JSONArray()
        events.forEach {
            arr.put(
                JSONObject().put("trackingId", it.trackingId).put("type", it.type)
                    .put("occurredAt", Instant.ofEpochMilli(it.occurredAtMillis).toString()),
            )
        }
        return call("POST", "/v1/events", token, JSONObject().put("events", arr).toString(), 200) { json ->
            val items = json.getJSONArray("items")
            List(items.length()) { i ->
                val o = items.getJSONObject(i)
                EventOutcome(o.getString("trackingId"), o.getString("outcome"))
            }
        }
    }

    private fun <T> call(
        method: String,
        path: String,
        token: String?,
        body: String?,
        expectedStatus: Int,
        parse: (JSONObject) -> T,
    ): ApiResult<T> {
        val raw = transport.execute(method, base + path, token, body) ?: return ApiResult.NetworkError
        if (raw.status != expectedStatus) return ApiResult.Http(raw.status, raw.retryAfterSeconds)
        return try {
            ApiResult.Ok(parse(if (raw.body.isBlank()) JSONObject() else JSONObject(raw.body)))
        } catch (e: JSONException) {
            ApiResult.NetworkError
        } catch (e: DateTimeParseException) {
            ApiResult.NetworkError
        }
    }

    private fun parseInstant(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
}
