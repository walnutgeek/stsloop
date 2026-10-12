package com.walnutgeek.stsloop.core.models

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** An HTTP GET from a byte offset: what a resumable download needs, and nothing else. */
fun interface RangeHttp {
    /** GET [url], asking for the bytes from [from] on when it is above 0. Throws [IOException] if no response. */
    fun get(url: String, from: Long): HttpBody
}

/**
 * A response. [rangeStart] is where a `206`'s `Content-Range` says the body
 * starts; [body] is empty unless the status is 2xx.
 */
class HttpBody(
    val status: Int,
    val rangeStart: Long?,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            body.close()
        } finally {
            onClose()
        }
    }
}

/**
 * [RangeHttp] over [HttpURLConnection], on the JDK and Android alike.
 * Redirects are followed (GitHub sends release assets to another host), and
 * the body is asked for as is: a transparently gzipped body would make byte
 * offsets meaningless.
 */
class UrlConnectionHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : RangeHttp {
    override fun get(url: String, from: Long): HttpBody {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "stsloop")
        if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
        try {
            val status = conn.responseCode
            val start = conn.getHeaderField("Content-Range")?.let { CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLong() }
            val body = if (status in 200..299) conn.inputStream else ByteArrayInputStream(ByteArray(0)) // nullInputStream() is API 33+
            return HttpBody(status, start, body) { conn.disconnect() }
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    private companion object {
        val CONTENT_RANGE = Regex("""bytes (\d+)-""")
    }
}
