package io.reliablepush.android.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.reliablepush.android.RegisterResult
import io.reliablepush.android.ReliablePush
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one place that composes the SDK's parts for one configuration (base URL, app id, retry job id): [DeviceApi],
 * [TokenStore] with the Keystore cipher, the [EventQueue] in `noBackupFilesDir/reliablepush`, [EventSender] and
 * [RetryScheduler]. Used by `ReliablePush` and by [RetryJobService] ([fromStoredConfig]).
 *
 * Every method here does storage or network I/O and must run on the SDK executor ([execute]); `TokenStore` writes
 * with a synchronous `commit()`.
 */
internal class Sdk private constructor(context: Context, val baseUrl: String, val appId: String, val jobId: Int) {
    private val app: Context = context.applicationContext
    val api = DeviceApi(baseUrl, appId, USER_AGENT)
    val store = TokenStore(app, cipherFactory())
    val queue = EventQueue(queueDir(app))
    val scheduler = RetryScheduler(app, jobId)

    fun sender(shouldStop: () -> Boolean = { false }) = EventSender(api, store, queue, clock, shouldStop)

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
                if (previous != null && previous.jobId != jobId) previous.scheduler.cancel()
                SdkLog.info("sdk.reconfigured", "app_id" to appId)
            }
            store.saveConfig(baseUrl, appId)
        }
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

    /** Queues [type] for [trackingId] and flushes; without a registration nothing is queued (WARN). */
    fun track(trackingId: String, type: String, occurredAtMillis: Long) {
        if (store.load() == null) {
            SdkLog.warn("tracking.not_registered", "tid" to trackingId, "type" to type)
            return
        }
        queue.add(QueuedEvent(trackingId, type, occurredAtMillis))
        flush()
    }

    /**
     * Flushes the queue under the retry-job policy: the job is scheduled before a flush that has events (so a process
     * death mid-flush still leaves a retry behind), cancelled when the flush empties the queue or nothing can be sent
     * (not registered; `register` flushes again), and rescheduled for a server `Retry-After` above the backoff start.
     */
    fun flush() {
        if (queue.size() == 0) return
        scheduler.schedule()
        when (val r = sender().flush()) {
            FlushResult.Done -> if (queue.size() == 0) scheduler.cancel()
            FlushResult.NotRegistered -> scheduler.cancel()
            is FlushResult.RetryLater -> {
                val delay = r.delaySeconds
                if (delay != null && delay > RetryScheduler.DEFAULT_LATENCY_SECONDS) scheduler.schedule(delay)
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

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** Test hook: Robolectric has no AndroidKeyStore. */
        @Volatile
        var cipherFactory: () -> TokenCipher = { KeystoreTokenCipher() }

        private val clock: () -> Long = System::currentTimeMillis

        fun create(context: Context, baseUrl: String, appId: String, jobId: Int) = Sdk(context, baseUrl, appId, jobId)

        /** The configuration `configure` persisted, for the retry job; null when there is none (or it is invalid). Executor only. */
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
            executor.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    SdkLog.error("sdk.failed", "error" to t.javaClass.simpleName)
                }
            }
        }

        fun onMain(block: () -> Unit) {
            mainHandler.post(block)
        }

        /** Test hook: waits until everything submitted so far has run. */
        fun awaitIdle() {
            executor.submit {}.get(30, TimeUnit.SECONDS)
        }
    }
}
