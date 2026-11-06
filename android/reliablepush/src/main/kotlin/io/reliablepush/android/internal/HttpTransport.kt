package io.reliablepush.android.internal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Raw answer: status, body (capped at [HttpTransport.MAX_BODY_BYTES]) and numeric `Retry-After`. */
internal class RawResponse(val status: Int, val body: String, val retryAfterSeconds: Long?)

/** Thin `HttpURLConnection` wrapper: no redirects, fixed timeouts, capped body, always disconnects. */
internal class HttpTransport(private val userAgent: String, private val timeoutMs: Int = DEFAULT_TIMEOUT_MS) {

    /** Returns null on any I/O failure (including timeouts). Never logs. */
    fun execute(method: String, url: String, bearerToken: String?, jsonBody: String?): RawResponse? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, application/problem+json")
                if (bearerToken != null) setRequestProperty("Authorization", "Bearer $bearerToken")
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { readCapped(it) } ?: ""
            RawResponse(status, body, conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 })
        } catch (e: IOException) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < MAX_BODY_BYTES) {
            val n = input.read(buf, 0, minOf(buf.size, MAX_BODY_BYTES - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    internal companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        const val DEFAULT_TIMEOUT_MS = 10_000
    }
}
