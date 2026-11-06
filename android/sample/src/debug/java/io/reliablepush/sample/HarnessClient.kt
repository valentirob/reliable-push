package io.reliablepush.sample

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Debug build only: the harness console API (`/console/api/…`), which plays the customer's backend for the sample
 * (grants, real-device list, sends) and shows the notification state to the E2E tests. Blocking calls: never on the
 * main thread. Every POST carries `Content-Type: application/json` (the console API requires it). The grant is a
 * credential: it is returned to the caller, never logged.
 */
class HarnessClient(private val harnessUrl: String = BuildConfig.RP_HARNESS_URL) : SampleBackend {

    override fun grant(appId: String, userId: String): String =
        post("/console/api/grants", JSONObject().put("appId", appId).put("userId", userId), 201).getString("grant")

    override fun deviceRegistered(appId: String, userId: String, deviceId: String, fcmToken: String?) {
        val body = JSONObject().put("appId", appId).put("userId", userId).put("deviceId", deviceId)
            .put("platform", "ANDROID").put("label", "${Build.MODEL} (sample)")
        // the FCM registration token is a credential for the harness's real-FCM mode: sent, never logged
        if (fcmToken != null) body.put("fcmToken", fcmToken)
        post("/console/api/real-devices", body, 201)
    }

    override fun fcmTokenChanged(deviceId: String, fcmToken: String) {
        post("/console/api/real-devices/$deviceId/fcm-token", JSONObject().put("fcmToken", fcmToken), 200)
    }

    /** Sends a tracked push to the user (the harness registers it at the backend); returns the tracking id. */
    fun push(appId: String, userId: String, title: String, body: String, policy: String, timeoutMinutes: Int): String =
        post(
            "/console/api/push",
            JSONObject().put("appId", appId).put("userId", userId).put("title", title).put("body", body)
                .put("policy", policy).put("timeoutMinutes", timeoutMinutes),
            200,
        ).getString("trackingId")

    /** `{notification: {state, …}, timeline: […], webhooks: […]}` */
    fun notification(trackingId: String): JSONObject = JSONObject(get("/console/api/notifications/$trackingId"))

    /** Fallback receiver calls of the app for [trackingId]. */
    fun inboxCallsFor(appId: String, trackingId: String): Int {
        val calls = JSONObject(get("/console/api/inbox?appId=${enc(appId)}")).getJSONArray("calls")
        return (0 until calls.length()).count { calls.getJSONObject(it).optString("trackingId") == trackingId }
    }

    /** Backend log entries with `event.action` = [eventAction], newest first. */
    fun logs(eventAction: String): List<JSONObject> {
        val arr = JSONArray(get("/console/api/logs?eventAction=${enc(eventAction)}&limit=500"))
        return List(arr.length()) { arr.getJSONObject(it) }
    }

    fun reachable(): Boolean = try {
        get("/console/api/apps")
        true
    } catch (e: IOException) {
        false
    }

    private fun get(path: String): String = call("GET", path, null, 200)

    private fun post(path: String, body: JSONObject, expected: Int): JSONObject =
        JSONObject(call("POST", path, body.toString(), expected))

    private fun call(method: String, path: String, body: String?, expected: Int): String {
        val conn = URL(harnessUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) } ?: ""
            if (status != expected) throw IOException("$method $path answered $status: ${text.take(300)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
