package io.reliablepush.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.reliablepush.android.ReliablePush

/**
 * Debug build only: a push without FCM, from adb (the harness console's "adb" preview):
 *
 * ```
 * adb shell am broadcast -a io.reliablepush.sample.DEBUG_PUSH -n io.reliablepush.sample/.DebugPushReceiver \
 *     --es rp_tid <trackingId> --es title <title> --es body <body>
 * ```
 *
 * Protected by `android.permission.DUMP` (adb shell has it, other apps cannot get it). The string extras are the data
 * of the push; [ReliablePush.handle] runs off the main thread, like the FCM entry point, so `DELIVERED` is on disk
 * before the receiver finishes. Only the extras `rp_tid`, `title` and `body` are forwarded.
 */
class DebugPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // only the payload keys of a tracked push (spec §6), nothing else the sender put in the intent
        val data = PAYLOAD_KEYS.mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }.toMap()
        if (data.isEmpty()) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                ReliablePush.handle(app, data)
            } finally {
                pending.finish()
            }
        }, "debug-push").start()
    }

    private companion object {
        val PAYLOAD_KEYS = listOf("rp_tid", "title", "body")
    }
}
