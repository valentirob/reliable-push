package io.reliablepush.android.internal

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/**
 * The one-off retry job: any network, minimum latency (default 30 s), exponential backoff from 30 s, not persisted
 * across reboots (so no RECEIVE_BOOT_COMPLETED). Scheduling again with the same [jobId] replaces the pending job.
 * No periodic work: a minimum latency cannot be combined with a periodic job.
 */
internal class RetryScheduler(private val context: Context, private val jobId: Int) {
    private val scheduler: JobScheduler?
        get() = context.getSystemService(JobScheduler::class.java)

    fun schedule(minLatencySeconds: Long = DEFAULT_LATENCY_SECONDS) {
        val job = JobInfo.Builder(jobId, ComponentName(context, RetryJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(minLatencySeconds.coerceAtLeast(0) * 1000)
            .setBackoffCriteria(BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(false)
            .build()
        try {
            scheduler?.schedule(job)
        } catch (e: RuntimeException) { // e.g. the OS refuses (too many jobs); never crash a push path
            RpLog.warn("event=retry.schedule_failed")
        }
    }

    fun cancel() {
        scheduler?.cancel(jobId)
    }

    companion object {
        const val DEFAULT_LATENCY_SECONDS = 30L
        const val BACKOFF_MILLIS = 30_000L
    }
}
