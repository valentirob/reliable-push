package io.reliablepush.android.internal

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable AndroidKeyStore key, no user authentication.
 * Blob layout: 12-byte IV, then ciphertext with the 16-byte tag. A tampered blob or a lost key throws.
 */
internal class KeystoreTokenCipher : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        return try {
            encryptOnce(plain)
        } catch (e: GeneralSecurityException) { // key permanently invalidated or unrecoverable: replace it, retry once
            reset()
            encryptOnce(plain)
        }
    }

    private fun encryptOnce(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_SIZE) { "blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    override fun reset() {
        synchronized(LOCK) {
            try {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
            } catch (e: GeneralSecurityException) {
                // nothing more to do; the next key() call tries to generate over it
            }
        }
    }

    private fun key(): SecretKey = synchronized(LOCK) {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = try {
            ks.getKey(ALIAS, null) as? SecretKey
        } catch (e: GeneralSecurityException) { // UnrecoverableKeyException, KeyPermanentlyInvalidatedException
            ks.deleteEntry(ALIAS)
            null
        }
        existing ?: generate()
    }

    private fun generate(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        val LOCK = Any() // process-wide: all instances share one keystore alias
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "io.reliablepush.devicetoken"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
