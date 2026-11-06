package io.reliablepush.android.internal

import java.util.concurrent.atomic.AtomicBoolean

/** Allows one token refresh per process (one app launch). */
internal object RefreshGate {
    private val used = AtomicBoolean(false)

    fun tryAcquire(): Boolean = used.compareAndSet(false, true)

    /** Test only. */
    internal fun reset() = used.set(false)
}
