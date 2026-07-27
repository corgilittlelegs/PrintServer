package dev.jaspreet.printserver.http

import java.io.IOException
import java.io.InputStream

/**
 * Presents one HTTP request's body — decoded per [head]'s `Content-Length` or
 * `Transfer-Encoding: chunked` framing — as a plain [InputStream] that returns `-1` exactly at
 * the logical end of the body, regardless of framing, and never further.
 *
 * This lets a caller consume the body in two stages off the *same live stream* — first a bounded
 * IPP attribute-group parse (e.g. `IppInputStream.readPacket()`), then a streaming copy of
 * whatever document bytes remain — without ever buffering the whole body in memory, and without
 * over-consuming into the next pipelined request on a persistent connection: chunk framing
 * (chunk-size lines, per-chunk CRLF terminators, the terminal `0`-chunk and trailers) is consumed
 * transparently as part of reaching that `-1`, so once a caller has read this stream to
 * exhaustion, [from] is positioned exactly at the start of whatever comes next (the next
 * pipelined request's HTTP head, or EOF).
 *
 * Enforces [maxBytes] cumulatively as bytes are consumed, matching [BodyReader] and
 * [BodySpooler]'s existing cap behavior (checked against the declared `Content-Length` up front,
 * or cumulatively per chunk for chunked bodies, since there's no advance total to check there).
 */
class DecodedBodyInputStream(
    head: HttpHead,
    private val from: InputStream,
    private val maxBytes: Long = BodyReader.DEFAULT_MAX_BYTES,
) : InputStream() {

    private val chunked = head.get("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true

    // Content-Length path: how many more raw bytes belong to this body.
    private var contentLengthRemaining: Long = 0L

    // Chunked path: how many more raw bytes belong to the chunk currently being read.
    private var chunkRemaining: Long = 0L

    // Total bytes handed to the caller so far, across both framings — used for the
    // chunked path's cumulative cap check (Content-Length is checked once, up front).
    private var totalConsumed: Long = 0L

    private var finished: Boolean

    init {
        if (chunked) {
            finished = false
        } else {
            val length = head.get("Content-Length")?.trim()?.toLongOrNull() ?: 0L
            if (length > maxBytes) {
                throw BodyTooLargeException("Content-Length $length exceeds limit $maxBytes")
            }
            contentLengthRemaining = length
            finished = length == 0L
        }
    }

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n <= 0) -1 else (b[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (finished) return -1
        try {
            return if (chunked) readChunked(b, off, len) else readContentLength(b, off, len)
        } catch (e: Exception) {
            // Once framing breaks (bad chunk header, short read, over cap), this stream's
            // notion of "where the next chunk header/byte starts" is no longer trustworthy —
            // permanently stop rather than let a later caller (e.g. a post-failure drain) try
            // to keep parsing and throw a second, unrelated-looking exception that would mask
            // this one.
            finished = true
            throw e
        }
    }

    private fun readContentLength(b: ByteArray, off: Int, len: Int): Int {
        if (contentLengthRemaining <= 0L) {
            finished = true
            return -1
        }
        val toRead = minOf(len.toLong(), contentLengthRemaining).toInt()
        val n = from.read(b, off, toRead)
        if (n < 0) throw IOException("EOF mid-body, expected $contentLengthRemaining more bytes")
        contentLengthRemaining -= n
        totalConsumed += n
        if (contentLengthRemaining == 0L) finished = true
        return n
    }

    private fun readChunked(b: ByteArray, off: Int, len: Int): Int {
        if (chunkRemaining == 0L && !advanceChunk()) {
            finished = true
            return -1
        }
        val toRead = minOf(len.toLong(), chunkRemaining).toInt()
        val n = from.read(b, off, toRead)
        if (n < 0) throw IOException("EOF mid-chunk, expected $chunkRemaining more bytes")
        chunkRemaining -= n
        totalConsumed += n
        if (chunkRemaining == 0L) {
            val cr = from.read()
            val lf = from.read()
            if (cr != '\r'.code || lf != '\n'.code) throw IOException("Missing CRLF after chunk")
        }
        return n
    }

    /** Reads the next chunk-size line. Returns false (and consumes trailers) at the terminal 0-size chunk. */
    private fun advanceChunk(): Boolean {
        val sizeLine = HttpHead.readLine(from) ?: throw IOException("EOF at chunk size")
        val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
            ?: throw IOException("Bad chunk size: $sizeLine")
        if (size == 0L) {
            while (true) {
                val line = HttpHead.readLine(from) ?: throw IOException("EOF in trailers")
                if (line.isEmpty()) return false
            }
        }
        // A chunked body has no advance total, so the limit is checked cumulatively
        // per chunk instead of up front the way Content-Length allows.
        if (totalConsumed + size > maxBytes) {
            throw BodyTooLargeException("Chunked body exceeded limit $maxBytes at ${totalConsumed + size} bytes")
        }
        chunkRemaining = size
        return true
    }
}
