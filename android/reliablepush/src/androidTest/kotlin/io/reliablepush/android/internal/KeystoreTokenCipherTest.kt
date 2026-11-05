package io.reliablepush.android.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class KeystoreTokenCipherTest {
    private val cipher = KeystoreTokenCipher()

    @Test
    fun roundTripAndTamperDetection() {
        val plain = "device-token-value".toByteArray()
        val blob = cipher.encrypt(plain)
        assertEquals(12 + plain.size + 16, blob.size)
        assertFalse(blob.copyOfRange(12, 12 + plain.size).contentEquals(plain))
        assertArrayEquals(plain, cipher.decrypt(blob))
        // fresh IV on every call
        assertFalse(blob.contentEquals(cipher.encrypt(plain)))

        for (index in listOf(0, 12, blob.size - 1)) { // IV, ciphertext, tag
            val tampered = blob.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            try {
                cipher.decrypt(tampered)
                fail("expected AEADBadTagException at $index")
            } catch (expected: AEADBadTagException) {
            }
        }
        try {
            cipher.decrypt(ByteArray(5))
            fail("expected an exception for a short blob")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun resetReplacesTheKey() {
        val blob = cipher.encrypt("x".toByteArray())
        cipher.reset()
        try {
            cipher.decrypt(blob)
            fail("old blob must be unreadable after reset")
        } catch (expected: Exception) {
        }
        assertArrayEquals("y".toByteArray(), cipher.decrypt(cipher.encrypt("y".toByteArray())))
    }
}
