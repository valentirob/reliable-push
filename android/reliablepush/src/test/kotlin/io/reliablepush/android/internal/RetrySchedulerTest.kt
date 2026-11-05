package io.reliablepush.android.internal

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class RetrySchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val js get() = context.getSystemService(JobScheduler::class.java)
    private val id = 0x52500001

    @Test
    fun schedulesJobWithNetworkConstraintAndLatency() {
        RetryScheduler(context, id).schedule()
        val job = js.getPendingJob(id)!!
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(30_000L, job.minLatencyMillis)
        assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.backoffPolicy)
        assertEquals(30_000L, job.initialBackoffMillis)
        assertFalse(job.isPersisted)
        assertFalse(job.isPeriodic)
        assertEquals(ComponentName(context, RetryJobService::class.java), job.service)
    }

    @Test
    fun customLatencyReplacesPendingJob() {
        val s = RetryScheduler(context, id)
        s.schedule()
        s.schedule(120)
        assertEquals(1, js.allPendingJobs.size)
        assertEquals(120_000L, js.getPendingJob(id)!!.minLatencyMillis)
    }

    @Test
    fun cancelRemovesJob() {
        val s = RetryScheduler(context, id)
        s.schedule()
        s.cancel()
        assertNull(js.getPendingJob(id))
    }

    @Test
    fun serviceIsDeclaredPrivateWithBindPermission() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, RetryJobService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertFalse(info.exported)
        assertTrue(info.enabled)
    }
}
