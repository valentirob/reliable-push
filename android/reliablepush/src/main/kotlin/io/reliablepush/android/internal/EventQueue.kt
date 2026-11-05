package io.reliablepush.android.internal

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Persistent FIFO of events waiting to be sent, in `<dir>/events.json` (callers pass
 * `noBackupFilesDir/reliablepush`), written atomically with [AtomicFile]. Capped at [CAP] events, the oldest dropped
 * with a WARN. An event is identified by `trackingId + type`; adding an event that is already queued is a no-op.
 *
 * Every operation reads the file, so several instances over the same directory (SDK object and retry job) stay
 * consistent; mutual exclusion uses one process-wide lock rather than a per-instance one for the same reason.
 * A corrupt file counts as empty. A write failure is logged and the event is lost (no crash on a push path).
 */
internal class EventQueue(dir: File) {
    private val file = AtomicFile(File(dir, FILE_NAME))
    private val dir = dir

    fun add(event: QueuedEvent) = synchronized(LOCK) {
        val items = read() ?: return@synchronized
        if (items.any { it.event.key() == event.key() }) return@synchronized
        items.add(Entry(event, 0))
        var dropped = 0
        while (items.size > CAP) {
            items.removeAt(0)
            dropped++
        }
        if (write(items)) {
            SdkLog.info("tracking.event_queued", "tid" to event.trackingId, "type" to event.type)
        }
        if (dropped > 0) SdkLog.warn("tracking.event_dropped", "count" to dropped, "reason" to "queue_full")
    }

    /** The oldest [max] events whose key is not in [exclude]. */
    fun peek(max: Int, exclude: Set<String> = emptySet()): List<QueuedEvent> = synchronized(LOCK) {
        (read() ?: mutableListOf()).asSequence().map { it.event }.filter { it.key() !in exclude }.take(max).toList()
    }

    /** Removes events by key (`trackingId + type`, see [keyOf]). */
    fun remove(ids: Collection<String>) = synchronized(LOCK) {
        if (ids.isEmpty()) return@synchronized
        val set = ids.toHashSet()
        val items = read() ?: return@synchronized
        if (items.removeAll { it.event.key() in set }) write(items)
    }

    /**
     * Records that the events with these keys were sent but the server did not acknowledge them. An event missed in
     * [MAX_MISSES] flushes is removed. Returns the keys removed.
     */
    fun noteUnacknowledged(ids: Collection<String>): List<String> = synchronized(LOCK) {
        val set = ids.toHashSet()
        val items = read() ?: return@synchronized emptyList()
        val gone = ArrayList<String>()
        val it = items.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.event.key() !in set) continue
            e.misses++
            if (e.misses >= MAX_MISSES) {
                gone.add(e.event.key())
                it.remove()
            }
        }
        if (set.isNotEmpty()) write(items)
        gone
    }

    fun clear() = synchronized(LOCK) { file.delete() }

    fun size(): Int = synchronized(LOCK) { read()?.size ?: 0 }

    private class Entry(val event: QueuedEvent, var misses: Int)

    /** The stored entries; empty when there is no file or it is corrupt; null on an I/O error (do not overwrite then). */
    private fun read(): MutableList<Entry>? {
        val bytes = try {
            file.openRead().use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            return ArrayList() // no file yet
        } catch (e: IOException) {
            SdkLog.warn("tracking.event_dropped", "reason" to "queue_read_failed")
            return null
        }
        return try {
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            ArrayList<Entry>(arr.length()).also { out ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Entry(
                            QueuedEvent(o.getString("tid"), o.getString("type"), o.getLong("at")),
                            o.optInt("misses", 0),
                        ),
                    )
                }
            }
        } catch (e: JSONException) {
            SdkLog.warn("tracking.event_dropped", "reason" to "queue_corrupt")
            ArrayList()
        }
    }

    private fun write(items: List<Entry>): Boolean {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().put("tid", it.event.trackingId).put("type", it.event.type)
                    .put("at", it.event.occurredAtMillis).put("misses", it.misses),
            )
        }
        dir.mkdirs()
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            SdkLog.warn("tracking.event_dropped", "reason" to "queue_write_failed")
            return false
        }
        return try {
            out.write(arr.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
            true
        } catch (e: IOException) {
            file.failWrite(out)
            SdkLog.warn("tracking.event_dropped", "reason" to "queue_write_failed")
            false
        }
    }

    companion object {
        const val CAP = 500
        const val MAX_MISSES = 2
        private const val FILE_NAME = "events.json"
        private val LOCK = Any()

        fun keyOf(trackingId: String, type: String) = trackingId + type
    }

    private fun QueuedEvent.key() = keyOf(trackingId, type)
}
