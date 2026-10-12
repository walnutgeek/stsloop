package com.walnutgeek.stsloop.core.models

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A local HTTP server for the downloader's tests: serves byte arrays by path,
 * honours `Range: bytes=N-` like GitHub's release host, and can misbehave on
 * request: cut the body short, ignore Range, or redirect.
 */
class TestServer : AutoCloseable {
    data class Request(val path: String, val range: String?)

    private val bodies = mutableMapOf<String, ByteArray>()
    private val redirects = mutableMapOf<String, String>()

    /** Requests seen, in order. */
    val requests = CopyOnWriteArrayList<Request>()

    /** When set, the next response sends at most this many body bytes, then drops the connection. */
    @Volatile
    var cutAfter: Long? = null

    /** When true, a 206 says nothing of where its body starts. */
    @Volatile
    var omitContentRange = false

    /** When true, Range headers are ignored and every response is a full 200. */
    @Volatile
    var ignoreRange = false

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val range = ex.requestHeaders.getFirst("Range")
            requests += Request(path, range)
            redirects[path]?.let { to ->
                ex.responseHeaders.add("Location", url(to))
                ex.sendResponseHeaders(302, -1)
                ex.close()
                return@createContext
            }
            val body = bodies[path]
            if (body == null) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return@createContext
            }
            var from = 0L
            var status = 200
            if (range != null && !ignoreRange) {
                from = Regex("""bytes=(\d+)-""").find(range)!!.groupValues[1].toLong()
                if (from >= body.size) {
                    ex.responseHeaders.add("Content-Range", "bytes */${body.size}")
                    ex.sendResponseHeaders(416, -1)
                    ex.close()
                    return@createContext
                }
                status = 206
                if (!omitContentRange) ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
            }
            val length = body.size - from
            ex.sendResponseHeaders(status, length)
            val cut = cutAfter
            cutAfter = null
            val send = if (cut != null) minOf(cut, length) else length
            try {
                ex.responseBody.write(body, from.toInt(), send.toInt())
                ex.responseBody.flush()
            } finally {
                // Closing short of Content-Length drops the connection: the client sees a truncated body.
                ex.close()
            }
        }
        start()
    }

    fun serve(path: String, body: ByteArray) {
        bodies[path] = body
    }

    fun redirect(path: String, to: String) {
        redirects[path] = to
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    override fun close() = server.stop(0)
}
