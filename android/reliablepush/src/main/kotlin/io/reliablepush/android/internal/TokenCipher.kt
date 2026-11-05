package io.reliablepush.android.internal

/** Encrypts the device token at rest. Implementations throw on any failure to decrypt. */
internal interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}
