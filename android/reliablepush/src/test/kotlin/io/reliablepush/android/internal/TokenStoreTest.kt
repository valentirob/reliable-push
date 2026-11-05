package io.reliablepush.android.internal

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

private class XorCipher(var failOnDecrypt: Boolean = false) : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        if (failEncryptTimes > 0) { failEncryptTimes--; throw java.security.GeneralSecurityException("key invalidated") }
        return xor(plain)
    }
    private fun xor(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
    var failEncryptTimes = 0
    var resets = 0
    override fun reset() { resets++; failEncryptTimes = 0 }
    override fun decrypt(blob: ByteArray): ByteArray {
        if (failOnDecrypt) throw java.security.GeneralSecurityException("key gone")
        return xor(blob)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TokenStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("io.reliablepush.store", Context.MODE_PRIVATE)

    @Before
    fun clean() {
        prefs.edit().clear().commit()
        ShadowLog.clear()
    }

    @Test
    fun storeRoundTripsThroughCipher() {
        val jwt = fakeJwt(0, 100)
        val store = TokenStore(context, XorCipher())
        store.save(Registration("dev-1", jwt, 100_000))

        val loaded = TokenStore(context, XorCipher()).load()!!
        assertEquals("dev-1", loaded.deviceId)
        assertEquals(jwt, loaded.token.raw)
        assertEquals(100_000L, loaded.token.expiresAt)

        val rawDump = prefs.all.values.joinToString("|") { it.toString() }
        assertFalse(rawDump.contains(jwt))
        assertFalse(rawDump.contains(jwt.substringAfter('.').substringBefore('.')))
        assertEquals("dev-1", prefs.getString("device_id", null))
    }

    @Test
    fun unreadableTokenIsTreatedAsNotRegistered() {
        TokenStore(context, XorCipher()).save(Registration("dev-1", fakeJwt(0, 100), 100_000))
        val store = TokenStore(context, XorCipher(failOnDecrypt = true))

        assertNull(store.load())
        assertNull(prefs.getString("device_id", null))
        assertFalse(prefs.contains("token"))
        val warn = ShadowLog.getLogsForTag("ReliablePush").single { it.type == Log.WARN }
        assertEquals("event=token.unreadable", warn.msg)
    }

    @Test
    fun corruptBase64OrUnparsableTokenIsUnreadable() {
        prefs.edit().putString("device_id", "d").putString("token", "%%%not-base64").commit()
        assertNull(TokenStore(context, XorCipher()).load())
        assertTrue(prefs.all.none { it.key == "token" })

        val store = TokenStore(context, XorCipher())
        store.save(Registration("d", "not-a-jwt", 1))
        assertNull(store.load())
        assertNull(prefs.getString("device_id", null))
    }

    @Test
    fun saveRecoversWhenKeyIsReplaced() {
        val cipher = XorCipher().apply { failEncryptTimes = 1 }
        val store = TokenStore(context, cipher)
        assertTrue(store.save(Registration("dev-1", fakeJwt(0, 100), 100_000)))
        assertEquals(1, cipher.resets)
        assertEquals("dev-1", store.load()!!.deviceId)
    }

    @Test
    fun saveReturnsFalseWhenEncryptionKeepsFailing() {
        val cipher = object : TokenCipher {
            override fun encrypt(plain: ByteArray): ByteArray = throw java.security.GeneralSecurityException("x")
            override fun decrypt(blob: ByteArray): ByteArray = blob
            override fun reset() {}
        }
        val store = TokenStore(context, cipher)
        assertFalse(store.save(Registration("dev-1", fakeJwt(0, 100), 100_000)))
        assertNull(store.load())
    }

    @Test
    fun unreadableTokenResetsCipher() {
        TokenStore(context, XorCipher()).save(Registration("dev-1", fakeJwt(0, 100), 100_000))
        val cipher = XorCipher(failOnDecrypt = true)
        TokenStore(context, cipher).load()
        assertEquals(1, cipher.resets)
    }

    @Test
    fun loadWithoutRegistrationReturnsNullQuietly() {
        assertNull(TokenStore(context, XorCipher()).load())
        assertTrue(ShadowLog.getLogsForTag("ReliablePush").isEmpty())
    }

    @Test
    fun clearRemovesRegistrationButKeepsConfig() {
        val store = TokenStore(context, XorCipher())
        store.saveConfig("https://x.example", "news")
        store.save(Registration("dev-1", fakeJwt(0, 100), 100_000))
        store.clear()
        assertNull(store.load())
        assertEquals("https://x.example" to "news", store.loadConfig())
    }

    @Test
    fun configRoundTrips() {
        val store = TokenStore(context, XorCipher())
        assertNull(store.loadConfig())
        store.saveConfig("https://x.example", "news")
        assertNotNull(store.loadConfig())
        assertEquals("https://x.example" to "news", TokenStore(context, XorCipher()).loadConfig())
    }
}
