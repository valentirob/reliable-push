package io.reliablepush.android.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.reliablepush.android.RegisterResult
import io.reliablepush.android.ReliablePush
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What `ensureRegistered` must do, decided locally (spec §3.2). */
internal enum class RegistrationNeed {
    /** A device token with more than 20 % of its validity left and no `renewalNeeded`: nothing. */
    NONE,

    /** Unexpired, but `renewalNeeded` is set or less than 20 % is left: `POST /v1/devices/token:refresh`. */
    REFRESH,

    /** No (readable) token, or it is expired: ask the host for an ID token and register. */
    ID_TOKEN,
}

/**
 * The one place that composes the SDK's parts for one configuration (base URL, app id, retry job id): [DeviceApi],
 * [TokenStore] with the Keystore cipher, the [EventQueue] in `noBackupFilesDir/reliablepush`, [EventSender] and
 * [RetryScheduler]. Used by `ReliablePush` and by [RetryJobService] ([fromStoredConfig]).
 *
 * Threads: network work (and everything that decrypts the token) runs on the single SDK thread ([execute]), so
 * flushes never overlap. Recording an event ([persist]) is disk-only and runs on the caller's thread or on a second,
 * persist-only thread ([persistOffMain]) that never does network I/O, so an event is on disk without waiting for a
 * flush that may take tens of seconds. `TokenStore` writes with a synchronous `commit()`.
 *
 * Token renewal ([refresh], [register]) happens only when the app calls `ensureRegistered` or `register`, never on
 * the receive path or in a flush (spec §3.1, D5).
 */
internal class Sdk private constructor(context: Context, val baseUrl: String, val appId: String, val jobId: Int) {
    private val app: Context = context.applicationContext
    val api = DeviceApi(baseUrl, appId, USER_AGENT)
    val store = TokenStore(app, cipherFactory())
    val queue = queueFactory(queueDir(app))
    val scheduler = RetryScheduler(app, jobId)

    fun sender(shouldStop: () -> Boolean = { false }) = EventSender(api, store, queue, shouldStop)

    /**
     * Persists this configuration. A stored configuration with another base URL or app id is replaced, and the
     * registration, the queue and the retry job of the old one are cleared (their tokens and events are useless here).
     * Then sends whatever is still queued (e.g. events whose retry job did not survive a reboot).
     */
    fun applyConfig(previous: Sdk?) {
        val stored = store.loadConfig()
        if (stored != baseUrl to appId) {
            if (stored != null) {
                store.clear()
                queue.clear()
                scheduler.cancel()
                SdkLog.info("sdk.reconfigured", "app_id" to appId)
            }
            store.saveConfig(baseUrl, appId)
        }
        // one retry job only: the old id's job goes; flush() schedules under the new id if events remain
        if (previous != null && previous.jobId != jobId) previous.scheduler.cancel()
        flush()
    }

    fun register(idToken: String): RegisterResult {
        val knownDeviceId = store.load()?.deviceId
        return when (val r = api.register(idToken, knownDeviceId)) {
            is ApiResult.Ok ->
                if (store.save(r.value)) {
                    SdkLog.info("device.registered", "device_id" to r.value.deviceId)
                    RegisterResult.Success(r.value.deviceId)
                } else {
                    SdkLog.warn("device.register_failed", "reason" to "token_not_stored")
                    RegisterResult.Failed(null)
                }
            is ApiResult.Http -> {
                when (r.status) {
                    401 -> SdkLog.warn("auth.failed", "status" to 401, "call" to "register")
                    429 -> SdkLog.warn("rate_limit.exceeded", "retry_after" to r.retryAfterSeconds, "call" to "register")
                    else -> SdkLog.warn("device.register_failed", "status" to r.status)
                }
                RegisterResult.Failed(r.status)
            }
            is ApiResult.NetworkError -> {
                SdkLog.warn("device.register_failed", "reason" to "network")
                RegisterResult.Failed(null)
            }
        }
    }

    /** The local decision of `ensureRegistered`, in the order of spec §3.2; disk only (decrypts the token). */
    fun registrationNeed(nowMillis: Long = clock()): RegistrationNeed {
        val stored = store.load() ?: return RegistrationNeed.ID_TOKEN
        if (stored.token.expiresAt <= nowMillis) return RegistrationNeed.ID_TOKEN
        return if (store.renewalNeeded() || stored.token.needsRefresh(nowMillis)) {
            RegistrationNeed.REFRESH
        } else {
            RegistrationNeed.NONE
        }
    }

    /**
     * Renews the stored device token (`POST /v1/devices/token:refresh`); saving it clears `renewalNeeded`. Returns
     * `null` when there is no token or the backend answered `401` (the caller then takes the ID-token path); the
     * registration is kept then, so the next registration reuses its device id.
     */
    fun refresh(): RegisterResult? {
        val stored = store.load() ?: return null
        return when (val r = api.refresh(stored.token.raw)) {
            is ApiResult.Ok ->
                if (store.save(Registration(stored.deviceId, r.value.deviceToken, r.value.expiresAt))) {
                    SdkLog.info("token.refreshed", "device_id" to stored.deviceId)
                    RegisterResult.Success(stored.deviceId)
                } else {
                    SdkLog.warn("device.register_failed", "reason" to "token_not_stored", "call" to "refresh")
                    RegisterResult.Failed(null)
                }
            is ApiResult.Http -> when (r.status) {
                401 -> {
                    SdkLog.warn("auth.failed", "status" to 401, "call" to "refresh")
                    null
                }
                429 -> {
                    SdkLog.warn("rate_limit.exceeded", "retry_after" to r.retryAfterSeconds, "call" to "refresh")
                    RegisterResult.Failed(429)
                }
                else -> {
                    SdkLog.warn("device.register_failed", "status" to r.status, "call" to "refresh")
                    RegisterResult.Failed(r.status)
                }
            }
            is ApiResult.NetworkError -> {
                SdkLog.warn("device.register_failed", "reason" to "network", "call" to "refresh")
                RegisterResult.Failed(null)
            }
        }
    }

    /** Best-effort revoke, then local cleanup in any case. True when the backend confirmed or nothing was registered. */
    fun logout(): Boolean {
        val reg = store.load()
        val confirmed = reg == null || api.revoke(reg.token.raw) is ApiResult.Ok
        store.clear()
        queue.clear()
        scheduler.cancel()
        if (confirmed) {
            SdkLog.info("device.revoked", "device_id" to reg?.deviceId)
        } else {
            SdkLog.warn("device.revoked", "device_id" to reg?.deviceId, "confirmed" to false)
        }
        return confirmed
    }

    /**
     * Records [type] for [trackingId] durably: queued on disk and the retry job scheduled, so it survives the process
     * being killed right after. Disk only (no network, no decryption): the registration check is by presence of a
     * stored token; an unreadable token is found later by the flush. Returns false (WARN) when not registered.
     */
    fun persist(trackingId: String, type: String, occurredAtMillis: Long): Boolean {
        if (store.deviceId() == null) {
            SdkLog.warn("tracking.not_registered", "tid" to trackingId, "type" to type)
            return false
        }
        queue.add(QueuedEvent(trackingId, type, occurredAtMillis))
        // If the OS refuses the job, schedule() logs WARN retry.schedule_failed and never throws: the event stays
        // queued and goes out with the next flush (this push's immediate send, the next push, a successful
        // ensureRegistered/register, or the next configure).
        scheduler.schedule()
        return true
    }

    /** [persist], then a flush on the SDK thread. */
    fun record(trackingId: String, type: String, occurredAtMillis: Long) {
        if (persist(trackingId, type, occurredAtMillis)) execute { flush() }
    }

    /**
     * Flushes the queue under the retry-job policy: the job is scheduled before a flush that has events (so a process
     * death mid-flush still leaves a retry behind), cancelled when the flush empties the queue or nothing can be sent
     * (not registered, or the token was rejected with `401`; a successful `ensureRegistered` or `register` flushes
     * again), and rescheduled for a server `Retry-After` above the backoff start.
     */
    fun flush() {
        if (queue.size() == 0) return
        if (!scheduler.isPending()) scheduler.schedule() // persist() just did: do not reset the backoff on every burst event
        when (val r = sender().flush()) {
            FlushResult.Done -> if (queue.size() == 0) scheduler.cancel()
            FlushResult.NotRegistered -> scheduler.cancel()
            is FlushResult.RetryLater -> {
                val delay = r.delaySeconds
                if (delay != null && delay > RetryScheduler.DEFAULT_LATENCY_SECONDS) scheduler.schedule(delay.coerceAtMost(RetryScheduler.MAX_LATENCY_SECONDS))
            }
        }
    }

    companion object {
        const val USER_AGENT = "reliable-push-android/${ReliablePush.VERSION}"

        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** One background thread for all storage and network work of the SDK and its retry job: no concurrent flushes. */
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "rp-sdk").apply { isDaemon = true }
        }

        /** Disk-only work requested from the main thread (see [persistOffMain]); never network I/O. */
        private val persistExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "rp-persist").apply { isDaemon = true }
        }

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** Test hook: Robolectric has no AndroidKeyStore. */
        @Volatile
        var cipherFactory: () -> TokenCipher = { KeystoreTokenCipher() }

        /** Test hook: fault injection on the queue. */
        @Volatile
        var queueFactory: (File) -> EventQueue = { EventQueue(it) }

        /** Longest wait of a main-thread `handle` for its `DELIVERED` write before posting anyway. */
        const val MAIN_PERSIST_WAIT_MS = 500L

        private val clock: () -> Long = System::currentTimeMillis

        fun create(context: Context, baseUrl: String, appId: String, jobId: Int) = Sdk(context, baseUrl, appId, jobId)

        /**
         * The configuration `configure` persisted, for the retry job; null when there is none (or it is invalid).
         * Called on the SDK thread by [RetryJobService] and on the caller's thread by `ReliablePush.stateFor` (lazy
         * path of `handle` before `configure`): it only reads the preferences and builds the object, no network.
         */
        fun fromStoredConfig(context: Context, jobId: Int): Sdk? {
            val (baseUrl, appId) = TokenStore(context, cipherFactory()).loadConfig() ?: return null
            return try {
                create(context, baseUrl, appId, jobId)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /** `noBackupFilesDir/reliablepush`, where `events.json` lives. */
        fun queueDir(context: Context) = File(context.noBackupFilesDir, "reliablepush")

        fun isTrackingId(value: String) = UUID_PATTERN.matches(value)

        /** Runs [task] on the SDK thread. Nothing it throws reaches the host app (logged as ERROR, class name only). */
        fun execute(task: () -> Unit) {
            executor.execute { guarded(task) }
        }

        /**
         * Runs disk-only [task] before returning, except on the main thread, where it goes to the persist-only thread
         * (which is never held up by network calls). Push entry points (FCM) run on a worker thread, so the event is on
         * disk when `handle` returns there.
         */
        fun persistOffMain(task: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) persistExecutor.execute { guarded(task) } else guarded(task)
        }

        /**
         * Runs disk-only [task] and returns its result before the caller goes on: `handle` posts only once `DELIVERED`
         * is on disk. Off the main thread (the FCM entry point) it runs inline. On the main thread it runs on the
         * persist-only thread and the caller waits at most [MAIN_PERSIST_WAIT_MS]: past that (a stuck disk) it returns
         * `null`, the write completes later and the post is not held up. What [task] throws is rethrown.
         */
        fun <T> persistBeforePost(task: () -> T): T? {
            if (Looper.myLooper() != Looper.getMainLooper()) return task()
            val future = persistExecutor.submit(Callable { task() })
            return try {
                future.get(MAIN_PERSIST_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } catch (e: TimeoutException) {
                SdkLog.warn("tracking.persist_slow", "wait_ms" to MAIN_PERSIST_WAIT_MS)
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
        }

        private fun guarded(task: () -> Unit) {
            try {
                task()
            } catch (t: Throwable) {
                SdkLog.error("sdk.failed", "error" to t.javaClass.simpleName)
            }
        }

        fun onMain(block: () -> Unit) {
            mainHandler.post(block)
        }

        /** Runs [block] on the main thread after [delayMillis] (a timeout); [cancelOnMain] removes it. */
        fun onMainDelayed(delayMillis: Long, block: Runnable) {
            mainHandler.postDelayed(block, delayMillis)
        }

        fun cancelOnMain(block: Runnable) {
            mainHandler.removeCallbacks(block)
        }

        /** Test hook: waits until the persist-only thread has run everything submitted so far. */
        fun awaitPersisted() {
            persistExecutor.submit {}.get(30, TimeUnit.SECONDS)
        }

        /** Test hook: waits until both SDK threads have run everything submitted so far. */
        fun awaitIdle() {
            awaitPersisted()
            executor.submit {}.get(30, TimeUnit.SECONDS)
        }
    }
}
