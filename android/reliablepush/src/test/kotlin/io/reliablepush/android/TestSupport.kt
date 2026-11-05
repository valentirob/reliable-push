package io.reliablepush.android

import android.os.Looper
import io.reliablepush.android.internal.Sdk
import io.reliablepush.android.internal.TokenCipher
import org.robolectric.Shadows.shadowOf

/** Robolectric has no AndroidKeyStore: the tests store the token through this reversible stand-in. */
internal class FakeCipher : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray = ByteArray(plain.size) { (plain[it].toInt() xor 0x33).toByte() }
    override fun decrypt(blob: ByteArray): ByteArray = encrypt(blob)
    override fun reset() {}
}

/** Waits until the SDK executor has run everything submitted so far, then runs the main-thread callbacks. */
internal fun settle() {
    repeat(2) { // a callback may submit more work (and that work may post more callbacks)
        Sdk.awaitIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
