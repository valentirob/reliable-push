package io.reliablepush.android.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * The SDK logger: `android.util.Log`, tag [TAG], message `event=<name> key=value…`.
 *
 * WARN and ERROR are always written; DEBUG and INFO only when the host app is debuggable
 * ([ApplicationInfo.FLAG_DEBUGGABLE], read by [init]). Fields whose key names a token, a user id or notification
 * content ([DENIED_KEYS], case-insensitive) are never written, as a guard against mistakes at the call site; callers
 * still must not pass such values under other names. `rp_tid` (`tid`) and the device id are allowed.
 */
internal object SdkLog {
    const val TAG = "ReliablePush"

    private val DENIED_KEYS = setOf(
        "token", "idtoken", "id_token", "devicetoken", "device_token", "authorization",
        "userid", "user_id", "user", "title", "body",
    )

    @Volatile
    private var verbose = false

    /** Enables DEBUG/INFO when the host app is debuggable. Called by `configure` and by the retry job. */
    fun init(context: Context) {
        verbose = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    fun debug(event: String, vararg fields: Pair<String, Any?>) {
        if (verbose) Log.d(TAG, format(event, fields))
    }

    fun info(event: String, vararg fields: Pair<String, Any?>) {
        if (verbose) Log.i(TAG, format(event, fields))
    }

    fun warn(event: String, vararg fields: Pair<String, Any?>) {
        Log.w(TAG, format(event, fields))
    }

    fun error(event: String, vararg fields: Pair<String, Any?>) {
        Log.e(TAG, format(event, fields))
    }

    private fun format(event: String, fields: Array<out Pair<String, Any?>>): String = buildString {
        append("event=").append(event)
        for ((key, value) in fields) {
            if (key.lowercase() in DENIED_KEYS) continue
            append(' ').append(key).append('=').append(value)
        }
    }
}
