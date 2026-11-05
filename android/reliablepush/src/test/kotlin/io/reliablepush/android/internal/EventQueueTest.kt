package io.reliablepush.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class EventQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ev(i: Int, type: String = "DELIVERED") = QueuedEvent("tid-$i", type, 1000L + i)

    @Test
    fun queueSurvivesNewInstance() {
        val dir = File(tmp.root, "reliablepush")
        EventQueue(dir).apply { add(ev(1)); add(ev(2, "READ")) }
        val again = EventQueue(dir)
        assertEquals(listOf(ev(1), ev(2, "READ")), again.peek(10))
        assertEquals(2, again.size())
    }

    @Test
    fun capDropsOldestWithWarn() {
        ShadowLog.clear()
        val q = EventQueue(tmp.root)
        for (i in 1..501) q.add(ev(i))
        assertEquals(500, q.size())
        assertEquals("tid-2", q.peek(1).single().trackingId)
        assertTrue(ShadowLog.getLogs().any { it.msg.contains("event=tracking.event_dropped") && it.msg.contains("queue_full") })
    }

    @Test
    fun concurrentEnqueueKeepsEveryEvent() {
        val dir = tmp.root
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { t ->
            thread {
                start.await()
                val q = EventQueue(dir) // separate instances over the same file
                for (i in 0 until 50) q.add(ev(t * 50 + i))
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        val all = EventQueue(dir).peek(1000)
        assertEquals(400, all.map { it.trackingId }.toSet().size)
    }

    @Test
    fun removeAndClearAndDuplicate() {
        val q = EventQueue(tmp.root)
        q.add(ev(1)); q.add(ev(1)); q.add(ev(1, "READ")); q.add(ev(2))
        assertEquals(3, q.size())
        q.remove(listOf(EventQueue.keyOf("tid-1", "DELIVERED")))
        assertEquals(listOf(ev(1, "READ"), ev(2)), q.peek(10))
        assertEquals(listOf(ev(2)), q.peek(10, setOf(EventQueue.keyOf("tid-1", "READ"))))
        q.clear()
        assertEquals(0, q.size())
    }

    @Test
    fun corruptFileCountsAsEmpty() {
        ShadowLog.clear()
        File(tmp.root, "events.json").writeText("{not json")
        val q = EventQueue(tmp.root)
        assertEquals(0, q.size())
        assertTrue(ShadowLog.getLogs().any { it.type == android.util.Log.WARN && it.msg.contains("queue_corrupt") })
        q.add(ev(1))
        assertEquals(1, q.size())
    }

    @Test
    fun noteUnacknowledgedDropsOnSecondMiss() {
        val q = EventQueue(tmp.root)
        q.add(ev(1))
        val k = EventQueue.keyOf("tid-1", "DELIVERED")
        assertEquals(emptyList<String>(), q.noteUnacknowledged(listOf(k)))
        assertEquals(1, q.size())
        assertEquals(listOf(k), q.noteUnacknowledged(listOf(k)))
        assertEquals(0, q.size())
    }
}
