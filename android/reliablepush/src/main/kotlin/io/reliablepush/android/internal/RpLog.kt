package io.reliablepush.android.internal

import android.util.Log

/**
 * Minimal logger (`event=<name> key=value`, tag `ReliablePush`) until Task 4 introduces `SdkLog`.
 * WARN always; below WARN only when the caller says the host app is debuggable. Never pass tokens.
 */
internal object RpLog {
    const val TAG = "ReliablePush"

    fun warn(message: String) {
        Log.w(TAG, message)
    }

    fun info(message: String, enabled: Boolean) {
        if (enabled) Log.i(TAG, message)
    }
}
