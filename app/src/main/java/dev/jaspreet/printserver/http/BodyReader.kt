package dev.jaspreet.printserver.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Body exceeded the caller's [BodyReader.readAll] maxBytes — a print client sent (or claimed) too much data. */
class BodyTooLargeException(message: String) : IOException(message)

/** Consumes one HTTP body into memory, DECODING chunked framing (contrast BodyCopier, which copies it verbatim). */
object BodyReader {

    /** Default cap for an incoming print document: generous for a multi-page PDF, small enough to bound memory/spool use. */
    const val DEFAULT_MAX_BYTES = 200L * 1_000_000L

    fun readAll(head: HttpHead, from: InputStream, maxBytes: Long = DEFAULT_MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val te = head.get("Transfer-Encoding")
        if (te != null && te.contains("chunked", ignoreCase = true)) {
            readChunked(from, out, maxBytes)
        } else {
            val length = head.get("Content-Length")?.trim()?.toLongOrNull() ?: 0L
            if (length > maxBytes) {
                throw BodyTooLargeException("Content-Length $length exceeds limit $maxBytes")
            }
            copyExact(from, out, length)
        }
        return out.toByteArray()
    }

    private fun copyExact(from: InputStream, to: ByteArrayOutputStream, count: Long) {
        val buf = ByteArray(65536)
        var left = count
        while (left > 0) {
            val n = from.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("EOF mid-body, expected $left more bytes")
            to.write(buf, 0, n)
            left -= n
        }
    }

    private fun readChunked(from: InputStream, to: ByteArrayOutputStream, maxBytes: Long) {
        var total = 0L
        while (true) {
            val sizeLine = HttpHead.readLine(from) ?: throw IOException("EOF at chunk size")
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("Bad chunk size: $sizeLine")
            if (size == 0L) {
                while (true) {
                    val line = HttpHead.readLine(from) ?: throw IOException("EOF in trailers")
                    if (line.isEmpty()) return
                }
            }
            total += size
            // A chunked body has no advance total, so the limit is checked cumulatively
            // per chunk instead of up front the way Content-Length allows.
            if (total > maxBytes) {
                throw BodyTooLargeException("Chunked body exceeded limit $maxBytes at $total bytes")
            }
            copyExact(from, to, size)
            val cr = from.read(); val lf = from.read()
            if (cr != '\r'.code || lf != '\n'.code) throw IOException("Missing CRLF after chunk")
        }
    }
}
