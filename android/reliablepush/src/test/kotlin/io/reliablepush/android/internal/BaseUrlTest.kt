package io.reliablepush.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BaseUrlTest {
    @Test
    fun rejectsCleartextToRemoteHost() {
        assertThrows(IllegalArgumentException::class.java) { BaseUrl.validate("http://example.com") }
        assertThrows(IllegalArgumentException::class.java) { BaseUrl.validate("http://10.0.2.3:8080") }
        assertEquals("http://10.0.2.2:8080", BaseUrl.validate("http://10.0.2.2:8080"))
        assertEquals("http://localhost", BaseUrl.validate("http://localhost"))
        assertEquals("http://127.0.0.1:9", BaseUrl.validate("http://127.0.0.1:9"))
        assertEquals("https://x.example", BaseUrl.validate("https://x.example"))
    }

    @Test
    fun normalisesTrailingSlashesAndWhitespace() {
        assertEquals("https://x.example/api", BaseUrl.validate("  https://x.example/api//  "))
    }

    @Test
    fun rejectsMalformedUrls() {
        listOf("", "x.example", "ftp://x.example", "https://", "https://u:p@x.example", "https://x.example?a=1", "https://x.example#f")
            .forEach { bad -> assertThrows(bad, IllegalArgumentException::class.java) { BaseUrl.validate(bad) } }
    }

    @Test
    fun rejectsLookalikeHosts() {
        assertThrows(IllegalArgumentException::class.java) { BaseUrl.validate("http://localhost.evil.com") }
        assertThrows(IllegalArgumentException::class.java) { BaseUrl.validate("http://127.0.0.1.evil.com") }
    }
}
