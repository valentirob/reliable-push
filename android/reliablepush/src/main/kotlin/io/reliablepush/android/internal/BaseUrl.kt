package io.reliablepush.android.internal

import java.net.URI
import java.net.URISyntaxException

internal object BaseUrl {
    /** Hosts for which cleartext http is allowed (the emulator's alias of the host machine included). */
    private val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    /**
     * Returns [url] trimmed and without trailing slashes.
     * @throws IllegalArgumentException if it is not an absolute https URL (http only for [CLEARTEXT_HOSTS]), or carries
     * user info, a query or a fragment.
     */
    fun validate(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("baseUrl is not a valid URL")
        }
        val host = uri.host?.lowercase()
        require(!host.isNullOrEmpty()) { "baseUrl must be an absolute URL with a host" }
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "baseUrl must not contain user info, a query or a fragment"
        }
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" -> require(host in CLEARTEXT_HOSTS) { "baseUrl must use https (http is allowed only for $CLEARTEXT_HOSTS)" }
            else -> throw IllegalArgumentException("baseUrl must use https")
        }
        return trimmed
    }
}
