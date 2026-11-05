package io.reliablepush.android.internal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/** A request as the [TestBackend] saw it. Header names are lower-cased. */
internal data class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

/** A scripted answer. [delayMs] is slept before answering (to provoke client timeouts). */
internal data class ScriptedResponse(
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val delayMs: Long = 0,
)

/**
 * Tiny scripted HTTP server on 127.0.0.1 (ephemeral port) for unit tests. Answers come from [enqueue]d responses in
 * order, or from [handler] when set; an unscripted request gets 500.
 */
internal class TestBackend : AutoCloseable {
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile
    var handler: ((RecordedRequest) -> ScriptedResponse)? = null
    private val queue = LinkedBlockingQueue<ScriptedResponse>()

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/") { ex -> serve(ex) }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun enqueue(vararg responses: ScriptedResponse) = responses.forEach { queue.add(it) }

    private fun serve(ex: HttpExchange) {
        ex.use {
            val req = RecordedRequest(
                method = ex.requestMethod,
                path = ex.requestURI.rawPath,
                headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                body = ex.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            requests.add(req)
            val resp = handler?.invoke(req) ?: queue.poll() ?: ScriptedResponse(500)
            if (resp.delayMs > 0) Thread.sleep(resp.delayMs)
            resp.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = resp.body.toByteArray(Charsets.UTF_8)
            if (resp.status == 204 || bytes.isEmpty()) {
                ex.sendResponseHeaders(resp.status, -1)
            } else {
                ex.sendResponseHeaders(resp.status, bytes.size.toLong())
                ex.responseBody.write(bytes)
            }
        }
    }

    override fun close() = server.stop(0)
}
