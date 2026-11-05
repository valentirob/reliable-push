package io.reliablepush.android.internal

/** Result of [EventSender.flush]. */
internal sealed interface FlushResult {
    /** The queue is empty. */
    data object Done : FlushResult

    /** Events remain; try again later (the job's backoff paces it). [delaySeconds] is the server's `Retry-After`. */
    data class RetryLater(val delaySeconds: Long?) : FlushResult

    /** No usable registration (never registered, unreadable token, or the refresh was answered 401). */
    data object NotRegistered : FlushResult
}

/**
 * Sends queued events in batches of [BATCH_SIZE] to `POST /v1/events`.
 *
 * Token: refreshed first when [DeviceToken.needsRefresh] and the process-wide [RefreshGate] allows; on a 401 it is
 * refreshed once more (gate permitting) and the batch retried once. A refresh answered 401 clears the registration.
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
    private val clock: () -> Long,
    private val debugLogging: Boolean = false,
    private val shouldStop: () -> Boolean = { false },
) {
    fun flush(): FlushResult {
        val stored = store.load() ?: return FlushResult.NotRegistered
        if (queue.size() == 0) return FlushResult.Done

        var token = stored.token.raw
        if (stored.token.needsRefresh(clock()) && RefreshGate.tryAcquire()) {
            when (val r = refresh(stored.deviceId, token)) {
                is Refreshed -> token = r.token
                // A transient failure of the early refresh is not fatal: the current token may still be accepted.
                is RefreshFailed -> if (r.result == FlushResult.NotRegistered) return r.result
            }
        }

        val attempted = HashSet<String>()
        while (true) {
            if (shouldStop()) return FlushResult.RetryLater(null)
            val batch = queue.peek(BATCH_SIZE, attempted)
            if (batch.isEmpty()) break
            batch.forEach { attempted.add(EventQueue.keyOf(it.trackingId, it.type)) }

            var result = api.sendEvents(token, batch)
            if (result is ApiResult.Http && result.status == 401) {
                if (!RefreshGate.tryAcquire()) {
                    RpLog.warn("event=auth.failed status=401 refresh=exhausted")
                    return FlushResult.RetryLater(null)
                }
                when (val r = refresh(stored.deviceId, token)) {
                    is Refreshed -> token = r.token
                    is RefreshFailed -> return r.result
                }
                result = api.sendEvents(token, batch)
                if (result is ApiResult.Http && result.status == 401) {
                    RpLog.warn("event=auth.failed status=401 refresh=done")
                    return FlushResult.RetryLater(null)
                }
            }

            when (result) {
                is ApiResult.Ok -> handleOutcomes(batch, result.value)
                is ApiResult.NetworkError -> return FlushResult.RetryLater(null)
                is ApiResult.Http -> {
                    val s = result.status
                    if (s in 400..499 && s != 401 && s != 408 && s != 429) {
                        queue.remove(batch.map { EventQueue.keyOf(it.trackingId, it.type) })
                        RpLog.warn("event=tracking.event_dropped count=${batch.size} status=$s")
                    } else {
                        if (s == 429) RpLog.warn("event=rate_limit.exceeded retry_after=${result.retryAfterSeconds}")
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
        items.forEach { byId[it.trackingId] = it.outcome }
        val done = ArrayList<String>()
        val missed = ArrayList<String>()
        var sent = 0
        for (e in batch) {
            val key = EventQueue.keyOf(e.trackingId, e.type)
            when (val outcome = byId[e.trackingId]) {
                "ACCEPTED", "DUPLICATE", "LATE" -> { done.add(key); sent++ }
                "NOT_FOUND", "FORBIDDEN" -> {
                    done.add(key)
                    RpLog.warn("event=tracking.event_dropped count=1 outcome=$outcome tid=${e.trackingId}")
                }
                else -> missed.add(key)
            }
        }
        queue.remove(done)
        if (sent > 0) RpLog.info("event=tracking.event_sent count=$sent", debugLogging)
        if (missed.isNotEmpty()) {
            val gone = queue.noteUnacknowledged(missed)
            if (gone.isNotEmpty()) {
                RpLog.warn("event=tracking.event_dropped count=${gone.size} reason=unacknowledged")
            }
        }
    }

    private sealed interface RefreshOutcome
    private class Refreshed(val token: String) : RefreshOutcome
    private class RefreshFailed(val result: FlushResult) : RefreshOutcome

    private fun refresh(deviceId: String, token: String): RefreshOutcome {
        return when (val r = api.refresh(token)) {
            is ApiResult.Ok -> {
                val issued = r.value
                if (store.save(Registration(deviceId, issued.deviceToken, issued.expiresAt))) {
                    RpLog.info("event=token.refreshed", debugLogging)
                    Refreshed(issued.deviceToken)
                } else {
                    RefreshFailed(FlushResult.NotRegistered)
                }
            }
            is ApiResult.Http ->
                if (r.status == 401) {
                    store.clear()
                    RpLog.warn("event=auth.failed status=401 refresh=rejected")
                    RefreshFailed(FlushResult.NotRegistered)
                } else {
                    RefreshFailed(FlushResult.RetryLater(r.retryAfterSeconds))
                }
            is ApiResult.NetworkError -> RefreshFailed(FlushResult.RetryLater(null))
        }
    }

    companion object {
        const val BATCH_SIZE = 50
    }
}
