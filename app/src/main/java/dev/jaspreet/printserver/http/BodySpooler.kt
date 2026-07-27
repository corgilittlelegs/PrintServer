package dev.jaspreet.printserver.http

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Streams one HTTP body straight to a caller-provided [File], decoding chunked framing the same
 * way [BodyReader] does but without ever materializing the whole body as a `ByteArray` — the
 * point for large print documents (up to [BodyReader.DEFAULT_MAX_BYTES]).
 *
 * Any thrown exception (oversized body, malformed chunk framing, short read) deletes whatever
 * partial bytes were already written, so the caller never has to clean up a half-written spool
 * file itself.
 */
object BodySpooler {

    /** Bounded copy buffer, matching [BodyReader]'s in-memory copy buffer size. */
    private const val BUFFER_SIZE = 65536

    /**
     * Decodes [head]'s body from [from] and writes it to [to], creating/truncating the file.
     * Returns the number of bytes written on success.
     */
    fun spool(head: HttpHead, from: InputStream, to: File, maxBytes: Long = BodyReader.DEFAULT_MAX_BYTES): Long {
        try {
            FileOutputStream(to).use { out ->
                val te = head.get("Transfer-Encoding")
                return if (te != null && te.contains("chunked", ignoreCase = true)) {
                    spoolChunked(from, out, maxBytes)
                } else {
                    val length = head.get("Content-Length")?.trim()?.toLongOrNull() ?: 0L
                    if (length > maxBytes) {
                        throw BodyTooLargeException("Content-Length $length exceeds limit $maxBytes")
                    }
                    copyExact(from, out, length)
                    length
                }
            }
        } catch (e: Exception) {
            to.delete()
            throw e
        }
    }

    private fun copyExact(from: InputStream, to: OutputStream, count: Long) {
        val buf = ByteArray(BUFFER_SIZE)
        var left = count
        while (left > 0) {
            val n = from.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("EOF mid-body, expected $left more bytes")
            to.write(buf, 0, n)
            left -= n
        }
    }

    private fun spoolChunked(from: InputStream, to: OutputStream, maxBytes: Long): Long {
        var total = 0L
        while (true) {
            val sizeLine = HttpHead.readLine(from) ?: throw IOException("EOF at chunk size")
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("Bad chunk size: $sizeLine")
            if (size == 0L) {
                while (true) {
                    val line = HttpHead.readLine(from) ?: throw IOException("EOF in trailers")
                    if (line.isEmpty()) return total
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
