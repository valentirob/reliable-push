package io.reliablepush.android.internal

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.pm.ApplicationInfo
import io.reliablepush.android.ReliablePush
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [EventSender.flush] for the one-off retry job on a background thread. Without config or registration it ends
 * without reschedule. On [onStopJob] (runtime limit, constraints lost) it stops sending further batches and asks for a
 * reschedule. Rescheduling after [FlushResult.RetryLater] uses the job's exponential backoff (a longer server
 * `Retry-After` is therefore not honoured exactly; a too-early retry is answered 429 again and stays bounded).
 */
internal class RetryJobService : JobService() {
    private val stopped = AtomicBoolean(false)

    override fun onStartJob(params: JobParameters): Boolean {
        stopped.set(false)
        Thread({
            val result = try {
                runFlush()
            } catch (e: Exception) { // never crash the host app from a job
                RpLog.warn("event=retry.failed")
                FlushResult.RetryLater(null)
            }
            if (!stopped.get()) jobFinished(params, result is FlushResult.RetryLater)
        }, "rp-retry").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped.set(true)
        return true
    }

    private fun runFlush(): FlushResult {
        val store = TokenStore(this, KeystoreTokenCipher())
        val (baseUrl, appId) = store.loadConfig() ?: return FlushResult.NotRegistered
        val api = DeviceApi(baseUrl, appId, "reliable-push-android/${ReliablePush.VERSION}")
        val debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val queue = EventQueue(defaultQueueDir(this), debug)
        return EventSender(api, store, queue, System::currentTimeMillis, debug) { stopped.get() }.flush()
    }

    companion object {
        /** `noBackupFilesDir/reliablepush`, where `events.json` lives. */
        fun defaultQueueDir(context: android.content.Context) = File(context.noBackupFilesDir, "reliablepush")
    }
}
