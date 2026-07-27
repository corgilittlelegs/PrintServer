package dev.jaspreet.printserver.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.SequenceInputStream

class DecodedBodyInputStreamTest {

    private fun head(vararg headers: Pair<String, String>) =
        HttpHead("POST / HTTP/1.1", headers.toList())

    private fun readAll(stream: DecodedBodyInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(37) // deliberately not a multiple of anything, to exercise boundary handling
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun `decodes a content-length body and stops exactly at its end`() {
        val raw = ByteArrayInputStream("helloNEXTREQUEST".toByteArray())
        val body = DecodedBodyInputStream(head("Content-Length" to "5"), raw)
        assertEquals("hello", String(readAll(body)))
        // The next bytes on the raw stream must be untouched — proves no over-read.
        assertEquals("NEXTREQUEST", String(raw.readBytes()))
    }

    @Test
    fun `decodes a chunked body and consumes its terminal marker, leaving the raw stream ready for the next request`() {
        val chunked = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\nNEXTREQUEST"
        val raw = ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1))
        val body = DecodedBodyInputStream(head("Transfer-Encoding" to "chunked"), raw)
        assertEquals("Wikipedia", String(readAll(body)))
        assertEquals("NEXTREQUEST", String(raw.readBytes()))
    }

    @Test
    fun `two bodies back to back on one stream both decode correctly, proving persistent-connection framing`() {
        val firstChunked = "3\r\nfoo\r\n0\r\n\r\n"
        val secondFixed = "second-body"
        val raw = SequenceInputStream(
            ByteArrayInputStream(firstChunked.toByteArray(Charsets.ISO_8859_1)),
            ByteArrayInputStream(secondFixed.toByteArray()),
        )
        val first = DecodedBodyInputStream(head("Transfer-Encoding" to "chunked"), raw)
        assertEquals("foo", String(readAll(first)))

        val second = DecodedBodyInputStream(head("Content-Length" to secondFixed.length.toString()), raw)
        assertEquals(secondFixed, String(readAll(second)))
    }

    @Test
    fun `rejects an oversized content-length up front`() {
        try {
            DecodedBodyInputStream(head("Content-Length" to "999999999999"), ByteArrayInputStream(ByteArray(0)), maxBytes = 1024)
            org.junit.Assert.fail("expected BodyTooLargeException")
        } catch (e: BodyTooLargeException) {
            // expected
        }
    }

    @Test
    fun `rejects a chunked body whose cumulative size exceeds the limit`() {
        val chunk = "C8\r\n" + "x".repeat(200) + "\r\n"
        val chunked = chunk.repeat(10) + "0\r\n\r\n"
        val body = DecodedBodyInputStream(
            head("Transfer-Encoding" to "chunked"),
            ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1)),
            maxBytes = 1024,
        )
        try {
            readAll(body)
            org.junit.Assert.fail("expected BodyTooLargeException")
        } catch (e: BodyTooLargeException) {
            // expected
        }
    }

    @Test
    fun `a failed read permanently stops the stream instead of misparsing subsequent bytes`() {
        // Malformed chunk-size line, followed by bytes that happen to look like more framing —
        // a naive implementation that kept trying after the first failure could produce a
        // second, different, misleading exception (or silently "succeed") instead of
        // consistently reporting the original failure.
        val chunked = "ZZZ\r\n4\r\nWiki\r\n0\r\n\r\n"
        val body = DecodedBodyInputStream(head("Transfer-Encoding" to "chunked"), ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1)))
        try {
            readAll(body)
            org.junit.Assert.fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Bad chunk size") == true)
        }
        // Subsequent reads must not attempt to reparse — they just report end-of-stream.
        assertEquals(-1, body.read())
        assertEquals(-1, body.read(ByteArray(10)))
    }

    @Test
    fun `no framing means an immediately empty body`() {
        val body = DecodedBodyInputStream(head(), ByteArrayInputStream("x".toByteArray()))
        assertEquals(-1, body.read())
    }

    @Test
    fun `streams a large multi-chunk body byte-for-byte through a small read buffer`() {
        val chunks = (1..25).map { i -> ByteArray(7000 + i * 37) { (it and 0xFF).toByte() } }
        val payload = ByteArrayOutputStream()
        for (c in chunks) payload.writeBytes(c)
        val framed = ByteArrayOutputStream()
        for (c in chunks) {
            framed.writeBytes((c.size.toString(16) + "\r\n").toByteArray(Charsets.ISO_8859_1))
            framed.writeBytes(c)
            framed.writeBytes("\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        framed.writeBytes("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))

        val body = DecodedBodyInputStream(
            head("Transfer-Encoding" to "chunked"),
            ByteArrayInputStream(framed.toByteArray()),
            maxBytes = 10_000_000,
        )
        assertArrayEquals(payload.toByteArray(), readAll(body))
    }

    @Test
    fun `truncated content-length body throws instead of returning a short read silently`() {
        val body = DecodedBodyInputStream(head("Content-Length" to "100"), ByteArrayInputStream("short".toByteArray()))
        try {
            readAll(body)
            org.junit.Assert.fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(-1, body.read())
    }
}
