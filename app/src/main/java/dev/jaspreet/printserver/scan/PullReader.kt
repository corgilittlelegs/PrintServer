package dev.jaspreet.printserver.scan

import java.io.IOException

/**
 * Minimal pull-based buffered byte reader over something that yields more bytes on
 * demand (a real `UsbTransport.read()` call in production, a scripted queue in tests).
 * Lets callers ask for "one CRLF-terminated line" or "exactly N bytes" without caring
 * how the underlying source happened to chunk its reads.
 */
class PullReader(private val fill: () -> ByteArray) {
    private var buf = ByteArray(0)
    private var pos = 0

    private fun pullMore() {
        val more = fill()
        if (more.isEmpty()) throw IOException("PullReader: source returned no data")
        buf = if (pos == 0) buf + more else buf.copyOfRange(pos, buf.size) + more
        pos = 0
    }

    /** Reads and consumes one CRLF-terminated line; the CRLF itself is not included. */
    fun readLine(): String {
        while (true) {
            var i = pos
            while (i + 1 < buf.size) {
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
        while (buf.size - pos < n) pullMore()
        val result = buf.copyOfRange(pos, pos + n)
        pos += n
        return result
    }
}
