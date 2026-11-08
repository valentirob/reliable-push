package io.reliablepush.android.internal

/** Result of [EventSender.flush]. */
internal sealed interface FlushResult {
    /** The queue is empty. */
    data object Done : FlushResult

    /** Events remain; try again later (the job's backoff paces it). [delaySeconds] is the server's `Retry-After`. */
    data class RetryLater(val delaySeconds: Long?) : FlushResult

    /**
     * Nothing can be sent until the app acts: never registered, unreadable token, or the events endpoint answered `401`
     * (the token is past the backend's grace period or revoked; `renewalNeeded` is then set and the events stay queued
     * for the flush that follows a successful `ensureRegistered` or `register`).
     */
    data object NotRegistered : FlushResult
}

/**
 * Sends queued events in batches of [BATCH_SIZE] to `POST /v1/events`.
 *
 * Token: the stored one, as it is. A flush **never renews it** (spec §3.1, D5): the backend accepts an expired token on
 * this endpoint for its grace period, and renewal is the job of `ReliablePush.ensureRegistered`. A `401` keeps the
 * events, sets the stored flag `renewalNeeded` (WARN `auth.failed`) and ends the flush with [FlushResult.NotRegistered],
 * so the retry job stops instead of resending a rejected token.
 *
 * Per-batch status handling: 200 removes events the server acknowledged (outcome ACCEPTED, DUPLICATE, LATE, NOT_FOUND
 * and FORBIDDEN; the last two with a WARN). Only a 4xx other than 401, 408 and 429 drops the batch (WARN
 * `tracking.event_dropped`). 408, 429 (WARN `rate_limit.exceeded`), 5xx, 3xx, an unexpected 2xx/1xx and I/O errors
 * keep the events and return [FlushResult.RetryLater]; a flush never retries a failing batch itself, so the retry
 * rate is bounded by the job's exponential backoff.
 *
 * Unacknowledged events: a 200 whose `items` do not mention a sent event (empty, missing or unknown outcome) keeps
 * that event for the next flush, but an event unacknowledged in two flushes ([EventQueue.MAX_MISSES]) is dropped with
 * a WARN, so a server that never echoes an id cannot keep it forever. Outcomes are matched by `trackingId` only (the
 * API does not echo the type), so a mention acknowledges every sent event with that id.
 *
 * [shouldStop] is polled between batches (a job has limited runtime); when it returns true the flush ends with
 * [FlushResult.RetryLater].
 */
internal class EventSender(
    private val api: DeviceApi,
    private val store: TokenStore,
    private val queue: EventQueue,
    private val shouldStop: () -> Boolean = { false },
) {
    fun flush(): FlushResult {
        val stored = store.load() ?: return FlushResult.NotRegistered
        if (queue.size() == 0) return FlushResult.Done

        val token = stored.token.raw
        val attempted = HashSet<String>()
        while (true) {
            if (shouldStop()) return FlushResult.RetryLater(null)
            val batch = queue.peek(BATCH_SIZE, attempted)
            if (batch.isEmpty()) break
            batch.forEach { attempted.add(EventQueue.keyOf(it.trackingId, it.type)) }

            val result = api.sendEvents(token, batch)
            if (result is ApiResult.Http && result.status == 401) {
                store.setRenewalNeeded()
                SdkLog.warn("auth.failed", "status" to 401, "call" to "events")
                return FlushResult.NotRegistered
            }

            when (result) {
                is ApiResult.Ok -> handleOutcomes(batch, result.value)
                is ApiResult.NetworkError -> return FlushResult.RetryLater(null)
                is ApiResult.Http -> {
                    val s = result.status
                    if (s in 400..499 && s != 401 && s != 408 && s != 429) {
                        queue.remove(batch.map { EventQueue.keyOf(it.trackingId, it.type) })
                        SdkLog.warn("tracking.event_dropped", "count" to batch.size, "status" to s)
                    } else {
                        if (s == 429) SdkLog.warn("rate_limit.exceeded", "retry_after" to result.retryAfterSeconds)
                        return FlushResult.RetryLater(result.retryAfterSeconds)
                    }
                }
            }
        }
        // whatever is left was unacknowledged and is still within its retry allowance
        return if (queue.size() > 0) FlushResult.RetryLater(null) else FlushResult.Done
    }

    private fun handleOutcomes(batch: List<QueuedEvent>, items: List<EventOutcome>) {
        val byId = HashMap<String, String>()
        items.forEach { byId[it.trackingId.lowercase()] = it.outcome } // case-insensitive: the backend echoes lower case
        val done = ArrayList<String>()
        val missed = ArrayList<String>()
        var sent = 0
        for (e in batch) {
            val key = EventQueue.keyOf(e.trackingId, e.type)
            when (val outcome = byId[e.trackingId.lowercase()]) {
                "ACCEPTED", "DUPLICATE", "LATE" -> { done.add(key); sent++ }
                "NOT_FOUND", "FORBIDDEN" -> {
                    done.add(key)
                    SdkLog.warn("tracking.event_dropped", "count" to 1, "outcome" to outcome, "tid" to e.trackingId)
                }
                else -> missed.add(key)
            }
        }
        queue.remove(done)
        if (sent > 0) SdkLog.info("tracking.event_sent", "count" to sent)
        if (missed.isNotEmpty()) {
            val gone = queue.noteUnacknowledged(missed)
            if (gone.isNotEmpty()) {
                SdkLog.warn("tracking.event_dropped", "count" to gone.size, "reason" to "unacknowledged")
            }
        }
    }

    companion object {
        const val BATCH_SIZE = 50
    }
}
