package io.reliablepush.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class RetryJobTest {
    private fun run(stopped: () -> Boolean = { false }, flush: () -> FlushResult): List<Reschedule> {
        val finished = ArrayList<Reschedule>()
        runJob(flush, stopped) { finished.add(it) }
        return finished
    }

    @Test
    fun decisions() {
        assertEquals(Reschedule.None, rescheduleDecision(FlushResult.Done))
        assertEquals(Reschedule.None, rescheduleDecision(FlushResult.NotRegistered))
        assertEquals(Reschedule.Backoff, rescheduleDecision(FlushResult.RetryLater(null)))
        assertEquals(Reschedule.Backoff, rescheduleDecision(FlushResult.RetryLater(30)))
        assertEquals(Reschedule.Backoff, rescheduleDecision(FlushResult.RetryLater(5)))
        assertEquals(Reschedule.After(31), rescheduleDecision(FlushResult.RetryLater(31)))
    }

    @Test
    fun happyPathFinishesOnceWithoutReschedule() =
        assertEquals(listOf(Reschedule.None), run { FlushResult.Done })

    @Test
    fun retryLaterBacksOffOrWaitsForRetryAfter() {
        assertEquals(listOf(Reschedule.Backoff), run { FlushResult.RetryLater(null) })
        assertEquals(listOf(Reschedule.After(120)), run { FlushResult.RetryLater(120) })
    }

    @Test
    fun noConfigOrRegistrationFinishesWithoutReschedule() =
        assertEquals(listOf(Reschedule.None), run { FlushResult.NotRegistered })

    @Test
    fun exceptionAndErrorFinishExactlyOnceWithBackoff() {
        assertEquals(listOf(Reschedule.Backoff), run { throw IllegalStateException("boom") })
        assertEquals(listOf(Reschedule.Backoff), run { throw NoClassDefFoundError("x") })
    }

    @Test
    fun stoppedJobDoesNotFinish() {
        assertTrue(run(stopped = { true }) { FlushResult.RetryLater(null) }.isEmpty())
        var stop = false
        assertTrue(run(stopped = { stop }) { stop = true; throw RuntimeException() }.isEmpty())
    }
}
