package dev.jaspreet.printserver.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class BodySpoolerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun head(vararg headers: Pair<String, String>) =
        HttpHead("POST / HTTP/1.1", headers.toList())

    /** An InputStream that only ever hands back [chunkSize] bytes per read() call, to exercise multi-read copying. */
    private class SlowStream(private val delegate: InputStream, private val chunkSize: Int) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, minOf(len, chunkSize))
    }

    @Test
    fun `spools content-length body to file`() {
        val dest = tmp.newFile("out.bin")
        val input = ByteArrayInputStream("helloEXTRA".toByteArray())
        val written = BodySpooler.spool(head("Content-Length" to "5"), input, dest)
        assertEquals(5L, written)
        assertEquals("hello", dest.readText())
    }

    @Test
    fun `rejects content-length body over the limit and deletes partial file`() {
        val dest = tmp.newFile("out.bin")
        try {
            BodySpooler.spool(
                head("Content-Length" to "999999999999"),
                ByteArrayInputStream(ByteArray(0)),
                dest,
                maxBytes = 1024,
            )
            org.junit.Assert.fail("expected BodyTooLargeException")
        } catch (e: BodyTooLargeException) {
            // expected
        }
        assertFalse("partial spool file should be deleted", dest.exists())
    }

    @Test
    fun `spools chunked body to file`() {
        val dest = tmp.newFile("out.bin")
        val chunked = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        val input = ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1))
        val written = BodySpooler.spool(head("Transfer-Encoding" to "chunked"), input, dest)
        assertEquals(9L, written)
        assertEquals("Wikipedia", dest.readText())
    }

    @Test
    fun `rejects chunked body whose cumulative size exceeds the limit and deletes partial file`() {
        val dest = tmp.newFile("out.bin")
        // Ten 200-byte chunks = 2000 bytes, over a 1024 limit.
        val chunk = "C8\r\n" + "x".repeat(200) + "\r\n"
        val chunked = chunk.repeat(10) + "0\r\n\r\n"
        try {
            BodySpooler.spool(
                head("Transfer-Encoding" to "chunked"),
                ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1)),
                dest,
                maxBytes = 1024,
            )
            org.junit.Assert.fail("expected BodyTooLargeException")
        } catch (e: BodyTooLargeException) {
            // expected
        }
        assertFalse("partial spool file should be deleted", dest.exists())
    }

    @Test
    fun `malformed chunk size leaves no partial file`() {
        val dest = tmp.newFile("out.bin")
        val chunked = "ZZZ\r\nWiki\r\n0\r\n\r\n"
        try {
            BodySpooler.spool(
                head("Transfer-Encoding" to "chunked"),
                ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1)),
                dest,
            )
            org.junit.Assert.fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertFalse("partial spool file should be deleted", dest.exists())
    }

    @Test
    fun `missing CRLF after chunk leaves no partial file`() {
        val dest = tmp.newFile("out.bin")
        // "Wiki" chunk not followed by CRLF before the next chunk-size line.
        val chunked = "4\r\nWikiXX5\r\npedia\r\n0\r\n\r\n"
        try {
            BodySpooler.spool(
                head("Transfer-Encoding" to "chunked"),
                ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1)),
                dest,
            )
            org.junit.Assert.fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertFalse("partial spool file should be deleted", dest.exists())
    }

    @Test
    fun `truncated content-length body leaves no partial file`() {
        val dest = tmp.newFile("out.bin")
        try {
            BodySpooler.spool(
                head("Content-Length" to "100"),
                ByteArrayInputStream("short".toByteArray()),
                dest,
            )
            org.junit.Assert.fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertFalse("partial spool file should be deleted", dest.exists())
    }

    @Test
    fun `no framing means empty file`() {
        val dest = tmp.newFile("out.bin")
        val written = BodySpooler.spool(head(), ByteArrayInputStream("x".toByteArray()), dest)
        assertEquals(0L, written)
        assertTrue(dest.exists())
        assertEquals(0L, dest.length())
    }

    @Test
    fun `streams a large multi-chunk body byte-for-byte through a small read buffer`() {
        val dest = tmp.newFile("out.bin")
        // Build a body well beyond a single 64KB copy-buffer pass, across many chunks of varying size.
        val chunks = (1..25).map { i -> ByteArray(7000 + i * 37) { (it and 0xFF).toByte() } }
        val payload = java.io.ByteArrayOutputStream()
        for (c in chunks) {
            payload.writeBytes(c)
        }
        val framed = java.io.ByteArrayOutputStream()
        for (c in chunks) {
            framed.writeBytes((c.size.toString(16) + "\r\n").toByteArray(Charsets.ISO_8859_1))
            framed.writeBytes(c)
            framed.writeBytes("\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        framed.writeBytes("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))

        // Force many small reads (17 bytes at a time) to exercise the streaming copy loop's boundary handling.
        val input = SlowStream(ByteArrayInputStream(framed.toByteArray()), 17)
        val written = BodySpooler.spool(
            head("Transfer-Encoding" to "chunked"),
            input,
            dest,
            maxBytes = 10_000_000,
        )

        val expected = payload.toByteArray()
        assertEquals(expected.size.toLong(), written)
        assertArrayEquals(expected, dest.readBytes())
    }
}
