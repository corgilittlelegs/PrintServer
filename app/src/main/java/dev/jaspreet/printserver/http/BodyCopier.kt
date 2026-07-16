package dev.jaspreet.printserver.http

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies exactly one HTTP message body from [from] to [to], using the framing
 * declared in [head] (Content-Length or chunked). Chunked bodies are copied
 * verbatim — framing bytes included — so the receiver can re-parse them.
 * IPP-USB responses are always framed; a head with neither header has no body.
 */
object BodyCopier {
    private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)

    fun copy(head: HttpHead, from: InputStream, to: OutputStream) {
        val te = head.get("Transfer-Encoding")
        val clValues = head.getAll("Content-Length")

        if (clValues.isNotEmpty() && clValues.toSet().size > 1) {
            throw IOException("Duplicate Content-Length headers with differing values: $clValues")
        }
        if (te != null && clValues.isNotEmpty()) {
            throw IOException("Message has both Content-Length and Transfer-Encoding headers")
        }
        if (te != null && te.contains("chunked", ignoreCase = true)) {
            copyChunked(from, to)
            return
        }
        if (clValues.isEmpty()) return
        val length = clValues[0].trim().toLongOrNull()
        if (length == null || length < 0) {
            throw IOException("Malformed Content-Length: ${clValues[0]}")
        }
        if (length > 0) copyExact(from, to, length)
    }

    private fun copyExact(from: InputStream, to: OutputStream, count: Long) {
        val buf = ByteArray(65536)
        var left = count
        while (left > 0) {
            val n = from.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("EOF mid-body, expected $left more bytes")
            to.write(buf, 0, n)
            left -= n
        }
    }

    private fun copyChunked(from: InputStream, to: OutputStream) {
        while (true) {
            val sizeLine = HttpHead.readLine(from) ?: throw IOException("EOF at chunk size")
            to.write(sizeLine.toByteArray(Charsets.ISO_8859_1)); to.write(CRLF)
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("Bad chunk size: $sizeLine")
            if (size > 0) {
                copyExact(from, to, size)
                expectCrlf(from)
                to.write(CRLF)
            } else {
                // trailer section: copy lines until the empty terminator line
                while (true) {
                    val line = HttpHead.readLine(from) ?: throw IOException("EOF in trailers")
                    to.write(line.toByteArray(Charsets.ISO_8859_1)); to.write(CRLF)
                    if (line.isEmpty()) return
                }
            }
        }
    }

    private fun expectCrlf(from: InputStream) {
        val cr = from.read(); val lf = from.read()
        if (cr != '\r'.code || lf != '\n'.code) throw IOException("Missing CRLF after chunk data")
    }
}
