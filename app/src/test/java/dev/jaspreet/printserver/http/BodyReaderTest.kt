package dev.jaspreet.printserver.http

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class BodyReaderTest {

    private fun head(vararg headers: Pair<String, String>) =
        HttpHead("POST / HTTP/1.1", headers.toList())

    @Test
    fun `reads content-length body`() {
        val input = ByteArrayInputStream("helloEXTRA".toByteArray())
        val body = BodyReader.readAll(head("Content-Length" to "5"), input)
        assertEquals("hello", String(body))
    }

    @Test
    fun `decodes chunked body`() {
        val chunked = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        val input = ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1))
        val body = BodyReader.readAll(head("Transfer-Encoding" to "chunked"), input)
        assertEquals("Wikipedia", String(body))
    }

    @Test
    fun `no framing means empty body`() {
        val body = BodyReader.readAll(head(), ByteArrayInputStream("x".toByteArray()))
        assertEquals(0, body.size)
    }

    @Test(expected = BodyTooLargeException::class)
    fun `rejects content-length body over the configured limit`() {
        BodyReader.readAll(
            head("Content-Length" to "999999999999"),
            ByteArrayInputStream(ByteArray(0)),
            maxBytes = 1024,
        )
    }

    @Test(expected = BodyTooLargeException::class)
    fun `rejects chunked body whose cumulative size exceeds the limit`() {
        // Ten 200-byte chunks = 2000 bytes, over a 1024 limit.
        val chunk = "C8\r\n" + "x".repeat(200) + "\r\n"
        val chunked = chunk.repeat(10) + "0\r\n\r\n"
        BodyReader.readAll(
            head("Transfer-Encoding" to "chunked"),
            ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1)),
            maxBytes = 1024,
        )
    }
}
