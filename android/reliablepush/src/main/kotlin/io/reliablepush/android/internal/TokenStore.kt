package io.reliablepush.android.internal

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log

internal data class StoredRegistration(val deviceId: String, val token: DeviceToken)

/**
 * Registration and config in private SharedPreferences. Only the device token is encrypted;
 * `deviceId` and `expiresAt` are plain. A token that cannot be read is dropped (not registered).
 */
internal class TokenStore(context: Context, private val cipher: TokenCipher) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(state: Registration) {
        val blob = Base64.encodeToString(cipher.encrypt(state.deviceToken.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit()
            .putString(KEY_DEVICE_ID, state.deviceId)
            .putString(KEY_TOKEN, blob)
            .putLong(KEY_EXPIRES_AT, state.expiresAt)
            .commit()
    }

    fun load(): StoredRegistration? {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        val blob = prefs.getString(KEY_TOKEN, null)
        if (deviceId == null && blob == null) return null
        if (deviceId == null || blob == null) return unreadable()
        return try {
            val raw = String(cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8)
            val token = DeviceToken.parse(raw) ?: return unreadable()
            StoredRegistration(deviceId, token)
        } catch (e: Exception) { // key invalidated, tampered blob, corrupt prefs
            unreadable()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_DEVICE_ID).remove(KEY_TOKEN).remove(KEY_EXPIRES_AT).commit()
    }

    fun saveConfig(baseUrl: String, appId: String) {
        prefs.edit().putString(KEY_BASE_URL, baseUrl).putString(KEY_APP_ID, appId).commit()
    }

    fun loadConfig(): Pair<String, String>? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val appId = prefs.getString(KEY_APP_ID, null) ?: return null
        return baseUrl to appId
    }

    private fun unreadable(): StoredRegistration? {
        clear()
        Log.w("ReliablePush", "event=token.unreadable") // Task 4 moves this to SdkLog
        return null
    }

    private companion object {
        const val PREFS = "io.reliablepush.store"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_BASE_URL = "base_url"
        const val KEY_APP_ID = "app_id"
    }
}
