package io.reliablepush.android.internal

import org.json.JSONObject
import java.util.Base64

/** A device token (ES256 JWT). Only `iat`/`exp` are read, no signature check on the device. Times in epoch millis. */
internal data class DeviceToken(val raw: String, val issuedAt: Long, val expiresAt: Long) {
    /** True when expired or the remaining lifetime is below 20 % of the total lifetime. */
    fun needsRefresh(nowMillis: Long): Boolean {
        val remaining = expiresAt - nowMillis
        return remaining <= 0 || remaining * 5 < (expiresAt - issuedAt)
    }

    companion object {
        fun parse(raw: String): DeviceToken? {
            val parts = raw.split('.')
            if (parts.size != 3) return null
            return try {
                val payload = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
                val iat = payload.getLong("iat")
                val exp = payload.getLong("exp")
                if (iat < 0 || exp <= iat || exp > Long.MAX_VALUE / 1000) return null
                DeviceToken(raw, iat * 1000, exp * 1000)
            } catch (e: Exception) { // bad base64, bad JSON, missing/non-numeric claims
                null
            }
        }
    }
}
