package io.reliablepush.android.internal

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.pm.ApplicationInfo
import io.reliablepush.android.ReliablePush
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** What to do with the job once a flush is over. */
internal sealed interface Reschedule {
    /** Finish, no retry (queue empty, or nothing can be sent). */
    data object None : Reschedule

    /** Finish asking the system to retry with the job's exponential backoff. */
    data object Backoff : Reschedule

    /** Finish, then schedule a fresh one-off job with this minimum latency (server `Retry-After`). */
    data class After(val seconds: Long) : Reschedule
}

/** A `Retry-After` above the backoff start ([RetryScheduler.DEFAULT_LATENCY_SECONDS]) is honoured by [Reschedule.After]. */
internal fun rescheduleDecision(result: FlushResult): Reschedule = when (result) {
    FlushResult.Done, FlushResult.NotRegistered -> Reschedule.None
    is FlushResult.RetryLater -> {
        val s = result.delaySeconds
        if (s != null && s > RetryScheduler.DEFAULT_LATENCY_SECONDS) Reschedule.After(s) else Reschedule.Backoff
    }
}

/**
 * The job contract: run [flush] (any Throwable counts as a failed flush -> backoff) and call [finish] exactly once,
 * unless the job was stopped meanwhile ([isStopped]); the system has then already rescheduled it and a late
 * `jobFinished` with stale parameters must not happen.
 */
internal fun runJob(flush: () -> FlushResult, isStopped: () -> Boolean, finish: (Reschedule) -> Unit) {
    val result = try {
        flush()
    } catch (t: Throwable) { // never crash the host app from a job, and always finish
        RpLog.warn("event=retry.failed")
        FlushResult.RetryLater(null)
    }
    if (isStopped()) return
    finish(rescheduleDecision(result))
}

/**
 * Thin glue: runs the flush for the one-off retry job on a background thread. Without config or registration it ends
 * without reschedule. [onStopJob] (runtime limit, constraints lost) flips the stop flag of that very start, so the
 * sender stops after the current batch, and returns true to have the job rescheduled.
 */
internal class RetryJobService : JobService() {
    // One flag per started job; a worker only ever looks at the flag it was started with.
    private val flags = ConcurrentHashMap<Int, AtomicBoolean>()

    override fun onStartJob(params: JobParameters): Boolean {
        val stopped = AtomicBoolean(false)
        flags[params.jobId] = stopped
        Thread({
            try {
                runJob({ flush(stopped) }, stopped::get) { finish(params, it) }
            } finally {
                flags.remove(params.jobId, stopped)
            }
        }, "rp-retry").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        flags[params.jobId]?.set(true)
        return true
    }

    private fun finish(params: JobParameters, how: Reschedule) {
        when (how) {
            Reschedule.None -> jobFinished(params, false)
            Reschedule.Backoff -> jobFinished(params, true)
            is Reschedule.After -> {
                jobFinished(params, false) // first, so the replacement is not taken for the still-running job
                RetryScheduler(this, params.jobId).schedule(how.seconds)
            }
        }
    }

    private fun flush(stopped: AtomicBoolean): FlushResult {
        val store = TokenStore(this, KeystoreTokenCipher())
        val (baseUrl, appId) = store.loadConfig() ?: return FlushResult.NotRegistered
        val api = DeviceApi(baseUrl, appId, "reliable-push-android/${ReliablePush.VERSION}")
        val debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val queue = EventQueue(defaultQueueDir(this), debug)
        return EventSender(api, store, queue, System::currentTimeMillis, debug) { stopped.get() }.flush()
    }

    companion object {
        /** `noBackupFilesDir/reliablepush`, where `events.json` lives. */
        fun defaultQueueDir(context: Context) = File(context.noBackupFilesDir, "reliablepush")
    }
}
