package dev.jaspreet.printserver.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkedHttpTest {

    /** Feeds the whole response as a queue of byte-array pieces, one per `fill()` call,
     *  so tests can exercise both "response arrives in one read" and "response arrives
     *  split across several reads" by varying how the pieces are chunked. */
    private fun readerOver(vararg pieces: ByteArray): PullReader {
        val queue = ArrayDeque(pieces.toList())
        return PullReader { queue.removeFirstOrNull() ?: throw java.io.IOException("exhausted") }
    }

    @Test
    fun `reads header lines up to the blank line, one read`() {
        val whole = "HTTP/1.1 200 OK\r\nLocation: /Scan/Jobs/JobList/1\r\n\r\n".toByteArray()
        val reader = readerOver(whole)
        val header = ChunkedHttp.readHeader(reader)
        assertEquals("HTTP/1.1 200 OK\r\nLocation: /Scan/Jobs/JobList/1\r\n", header)
    }

    @Test
    fun `reads header split across multiple underlying reads`() {
        val reader = readerOver(
            "HTTP/1.1 200".toByteArray(),
            " OK\r\nLocation:".toByteArray(),
            " /Scan/Jobs/JobList/1\r\n\r\n".toByteArray(),
        )
        val header = ChunkedHttp.readHeader(reader)
        assertEquals("HTTP/1.1 200 OK\r\nLocation: /Scan/Jobs/JobList/1\r\n", header)
    }

    @Test
    fun `skips stale preamble until the HTTP status line`() {
        val reader = readerOver(
            "0\r\n\r\n<old>stale</old>\r\n".toByteArray(),
            "HTTP/1.1 200 OK\r\nLocation: /Scan/Jobs/JobList/1\r\n\r\n".toByteArray(),
        )
        val header = ChunkedHttp.readHeader(reader)
        assertEquals("HTTP/1.1 200 OK\r\nLocation: /Scan/Jobs/JobList/1\r\n", header)
    }

    @Test
    fun `recognizes a created response from the status line`() {
        val header = "HTTP/1.1 201 Created\r\nLocation: /Scan/Jobs/JobList/1\r\n"
        assertEquals(true, ChunkedHttp.isCreated(header))
    }

    @Test
    fun `decodes a single-chunk body`() {
        val body = "<ScannerState>Idle</ScannerState>"
        val whole = ("${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n").toByteArray()
        val reader = readerOver(whole)
        val decoded = ChunkedHttp.readChunkedBody(reader)
        assertArrayEquals(body.toByteArray(), decoded)
    }

    @Test
    fun `decodes a multi-chunk body`() {
        val whole = ("5\r\nHello\r\n" + "6\r\n World\r\n" + "0\r\n\r\n").toByteArray()
        val reader = readerOver(whole)
        val decoded = ChunkedHttp.readChunkedBody(reader)
        assertArrayEquals("Hello World".toByteArray(), decoded)
    }

    @Test
    fun `decodes binary chunk data without treating it as text`() {
        val binaryChunk = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x0A) // JPEG-ish magic + a raw LF byte
        val whole = "${binaryChunk.size.toString(16)}\r\n".toByteArray() + binaryChunk + "\r\n0\r\n\r\n".toByteArray()
        val reader = readerOver(whole)
        val decoded = ChunkedHttp.readChunkedBody(reader)
        assertArrayEquals(binaryChunk, decoded)
    }
}
