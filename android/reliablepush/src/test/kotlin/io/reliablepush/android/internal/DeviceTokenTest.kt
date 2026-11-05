package io.reliablepush.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DeviceTokenTest {
    @Test
    fun parseReadsIatAndExpInMillis() {
        val t = DeviceToken.parse(fakeJwt(10, 110))!!
        assertEquals(10_000L, t.issuedAt)
        assertEquals(110_000L, t.expiresAt)
    }

    @Test
    fun needsRefreshBelowTwentyPercent() {
        val t = DeviceToken.parse(fakeJwt(0, 100))!!
        assertFalse(t.needsRefresh(80_000))
        assertTrue(t.needsRefresh(81_000))
        assertTrue(t.needsRefresh(100_000))
        assertTrue(t.needsRefresh(500_000))
    }

    @Test
    fun parseRejectsMalformedToken() {
        assertNull(DeviceToken.parse(""))
        assertNull(DeviceToken.parse("abc"))
        assertNull(DeviceToken.parse("a.b.c"))
        assertNull(DeviceToken.parse("a.!!!.c"))
        assertNull(DeviceToken.parse(fakeJwt(0, 100).replace(Regex("^[^.]+\\.[^.]+"), "x.e30")))
        // exp not after iat
        assertNull(DeviceToken.parse(fakeJwt(100, 100)))
        assertNull(DeviceToken.parse(fakeJwt(100, 50)))
        assertNotNull(DeviceToken.parse(fakeJwt(1, 2)))
    }
}
