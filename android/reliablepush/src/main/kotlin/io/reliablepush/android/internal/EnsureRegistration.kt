package io.reliablepush.android.internal

import io.reliablepush.android.RegisterResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The runs of `ReliablePush.ensureRegistered` (spec §3.2): one at a time per process. A call while a run is in progress
 * joins it (its callback gets the same result; its provider is not called).
 *
 * A run is a short sequence of steps on the SDK thread, never a thread blocked waiting for the host: the local decision
 * ([Sdk.registrationNeed]), then a refresh, or the ID-token provider. The provider gets a `deliver` function it calls
 * once, from any thread; extra calls are ignored. When it has not called it within [idTokenTimeoutMs] (a timer on the
 * main looper), the run ends [RegisterResult.NotLoggedIn]. A successful run flushes the queue. Nothing thrown inside
 * a run escapes: it ends [RegisterResult.Failed] with WARN `sdk.internal_error` (a throwing provider: `NotLoggedIn`).
 */
internal object EnsureRegistration {
    const val ID_TOKEN_TIMEOUT_MS = 30_000L

    /** Test hook. */
    @Volatile
    var idTokenTimeoutMs = ID_TOKEN_TIMEOUT_MS

    private val lock = Any()

    /** One run: the callbacks waiting for it (the starter's and the joined ones). */
    private class Run {
        val callbacks = ArrayList<((RegisterResult) -> Unit)?>()
    }

    /** The run in progress; `null` when none is. */
    private var current: Run? = null

    fun start(sdk: Sdk, provider: (deliver: (String?) -> Unit) -> Unit, callback: ((RegisterResult) -> Unit)?) {
        val run = synchronized(lock) {
            current?.let { running ->
                running.callbacks.add(callback)
                return
            }
            Run().also { it.callbacks.add(callback); current = it }
        }
        run.step { run.decide(sdk, provider) }
    }

    private fun Run.decide(sdk: Sdk, provider: (deliver: (String?) -> Unit) -> Unit) {
        when (sdk.registrationNeed()) {
            RegistrationNeed.NONE -> finish(RegisterResult.AlreadyRegistered)
            RegistrationNeed.REFRESH -> {
                val refreshed = sdk.refresh()
                if (refreshed == null) askForIdToken(sdk, provider) else complete(sdk, refreshed)
            }
            RegistrationNeed.ID_TOKEN -> askForIdToken(sdk, provider)
        }
    }

    private fun Run.askForIdToken(sdk: Sdk, provider: (deliver: (String?) -> Unit) -> Unit) {
        val answered = AtomicBoolean(false)
        val timeout = Runnable {
            if (answered.compareAndSet(false, true)) {
                SdkLog.warn("device.register_failed", "reason" to "id_token_timeout")
                finish(RegisterResult.NotLoggedIn)
            }
        }
        val deliver: (String?) -> Unit = { idToken ->
            if (answered.compareAndSet(false, true)) {
                Sdk.cancelOnMain(timeout)
                step {
                    if (idToken.isNullOrBlank()) finish(RegisterResult.NotLoggedIn) else complete(sdk, sdk.register(idToken))
                }
            }
        }
        Sdk.onMainDelayed(idTokenTimeoutMs, timeout)
        try {
            provider(deliver)
        } catch (t: Throwable) { // a host bug: no ID token
            SdkLog.warn("device.register_failed", "reason" to "id_token_provider_failed", "error" to t.javaClass.simpleName)
            deliver(null)
        }
    }

    private fun Run.complete(sdk: Sdk, result: RegisterResult) {
        finish(result)
        if (result is RegisterResult.Success) sdk.flush()
    }

    /** Runs [block] on the SDK thread; anything it throws ends the run `Failed(null)`. */
    private fun Run.step(block: () -> Unit) {
        try {
            Sdk.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    SdkLog.warn("sdk.internal_error", "error" to t.javaClass.simpleName)
                    finish(RegisterResult.Failed(null))
                }
            }
        } catch (t: Throwable) { // the executor refused the task
            SdkLog.warn("sdk.internal_error", "error" to t.javaClass.simpleName)
            finish(RegisterResult.Failed(null))
        }
    }

    /** Ends this run (once; later calls, or calls after a newer run started, do nothing) and calls back every waiter. */
    private fun Run.finish(result: RegisterResult) {
        synchronized(lock) {
            if (current !== this) return
            current = null
        }
        callbacks.forEach { callback -> if (callback != null) Sdk.onMain { callback(result) } }
    }

    /** Test only: forgets a run left in progress. */
    fun resetForTest() {
        synchronized(lock) { current = null }
        idTokenTimeoutMs = ID_TOKEN_TIMEOUT_MS
    }
}
