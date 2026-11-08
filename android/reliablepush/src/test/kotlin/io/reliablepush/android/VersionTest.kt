package io.reliablepush.android

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class VersionTest {
    @Test
    fun versionMatchesGradleProperty() {
        assertEquals("0.2.0", System.getProperty("rp.expectedVersion"))
        assertEquals(System.getProperty("rp.expectedVersion"), ReliablePush.VERSION)
    }
}
