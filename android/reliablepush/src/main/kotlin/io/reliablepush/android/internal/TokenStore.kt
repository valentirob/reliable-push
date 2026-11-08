package io.reliablepush.android.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

internal data class StoredRegistration(val deviceId: String, val token: DeviceToken)

/**
 * Registration and config in private SharedPreferences. Only the device token is encrypted;
 * `deviceId`, `expiresAt` and the `renewalNeeded` flag are plain. A token that cannot be read is dropped (not
 * registered).
 *
 * `renewalNeeded` is set when the events endpoint answered `401` (the token is past the backend's grace period or
 * revoked); `ensureRegistered` then renews the token. Saving a new token or clearing the registration clears it.
 */
// commit(), not apply(), on purpose: a push entry point may be killed as soon as it returns, so every write (token,
// renewalNeeded, config) must be on disk before the call that made it returns.
@SuppressLint("ApplySharedPref")
internal class TokenStore(context: Context, private val cipher: TokenCipher) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Returns false (and stores nothing) if the token cannot be encrypted even after replacing the key.
     * Never throws for crypto failures, so callers on a push path need no try/catch; the device then
     * simply counts as not registered.
     */
    fun save(state: Registration): Boolean {
        val bytes = state.deviceToken.toByteArray(Charsets.UTF_8)
        val encrypted = try {
            cipher.encrypt(bytes)
        } catch (e: Exception) {
            try {
                cipher.reset() // may throw too (KeyStore.load: IOException); a failed save must never throw
                cipher.encrypt(bytes)
            } catch (e2: Exception) {
                clear()
                return false
            }
        }
        prefs.edit()
            .putString(KEY_DEVICE_ID, state.deviceId)
            .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putLong(KEY_EXPIRES_AT, state.expiresAt)
            .remove(KEY_RENEWAL_NEEDED)
            .commit()
        return true
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

    /** The stored device id when a token is stored too; no decryption (so an unreadable token still shows its id). */
    fun deviceId(): String? = if (prefs.contains(KEY_TOKEN)) prefs.getString(KEY_DEVICE_ID, null) else null

    fun clear() {
        prefs.edit().remove(KEY_DEVICE_ID).remove(KEY_TOKEN).remove(KEY_EXPIRES_AT).remove(KEY_RENEWAL_NEEDED).commit()
    }

    fun renewalNeeded(): Boolean = prefs.getBoolean(KEY_RENEWAL_NEEDED, false)

    fun setRenewalNeeded() {
        prefs.edit().putBoolean(KEY_RENEWAL_NEEDED, true).commit()
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
        try { cipher.reset() } catch (e: Exception) { /* best effort */ }
        SdkLog.warn("token.unreadable")
        return null
    }

    private companion object {
        const val PREFS = "io.reliablepush.store"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_RENEWAL_NEEDED = "renewal_needed"
        const val KEY_BASE_URL = "base_url"
        const val KEY_APP_ID = "app_id"
    }
}
