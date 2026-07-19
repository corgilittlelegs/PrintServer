package dev.jaspreet.printserver.scan

import java.io.IOException

/**
 * Minimal pull-based buffered byte reader over something that yields more bytes on
 * demand (a real `UsbTransport.read()` call in production, a scripted queue in tests).
 * Lets callers ask for "one CRLF-terminated line" or "exactly N bytes" without caring
 * how the underlying source happened to chunk its reads.
 *
 * Internally uses a doubling buffer with separate read/write cursors so already-retained
 * bytes are never re-copied on each [pullMore] call (unlike a naive "concat and slice"
 * approach, which is O(n^2) total copying for data arriving in many small reads). Total
 * buffered size is capped at [MAX_BUFFERED_BYTES] to bound memory use against a
 * malformed or malicious response that never terminates.
 */
class PullReader(private val fill: () -> ByteArray) {
    companion object {
        /** Generous but bounded — far larger than any realistic single scanned page JPEG. */
        private const val MAX_BUFFERED_BYTES = 64 * 1024 * 1024
    }

    private var buf = ByteArray(4096)
    private var pos = 0 // read cursor
    private var limit = 0 // write cursor (end of valid data)

    private fun pullMore() {
        val more = fill()
        if (more.isEmpty()) throw IOException("PullReader: source returned no data")

        // Compact first: drop already-consumed bytes so growth only accounts for
        // still-unread data, not the whole history of the stream.
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, limit - pos)
            limit -= pos
            pos = 0
        }

        val needed = limit + more.size
        if (needed > MAX_BUFFERED_BYTES) {
            throw IOException("PullReader: response exceeded maximum size")
        }
        if (needed > buf.size) {
            var newSize = buf.size
            while (newSize < needed) newSize *= 2
            buf = buf.copyOf(newSize.coerceAtMost(MAX_BUFFERED_BYTES))
        }
        System.arraycopy(more, 0, buf, limit, more.size)
        limit += more.size
    }

    /** Reads and consumes one CRLF-terminated line; the CRLF itself is not included. */
    fun readLine(): String {
        while (true) {
            var i = pos
            while (i + 1 < limit) {
                if (buf[i] == 13.toByte() && buf[i + 1] == 10.toByte()) {
                    val line = String(buf, pos, i - pos, Charsets.US_ASCII)
                    pos = i + 2
                    return line
                }
                i++
            }
            pullMore()
        }
    }

    /** Reads and consumes exactly [n] bytes. */
    fun readExactly(n: Int): ByteArray {
        while (limit - pos < n) pullMore()
        val result = buf.copyOfRange(pos, pos + n)
        pos += n
        return result
    }
}
