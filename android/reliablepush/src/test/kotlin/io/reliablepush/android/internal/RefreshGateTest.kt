package io.reliablepush.android.internal

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RefreshGateTest {
    @Before @After
    fun reset() = RefreshGate.reset()

    @Test
    fun refreshGateAllowsOncePerProcess() {
        assertTrue(RefreshGate.tryAcquire())
        assertFalse(RefreshGate.tryAcquire())
        assertFalse(RefreshGate.tryAcquire())
        RefreshGate.reset()
        assertTrue(RefreshGate.tryAcquire())
    }
}
