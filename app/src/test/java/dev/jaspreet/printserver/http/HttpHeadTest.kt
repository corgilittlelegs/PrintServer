package dev.jaspreet.printserver.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpHeadTest {

    private fun stream(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `parses request line and headers`() {
        val head = HttpHead.parse(stream("POST /ipp/print HTTP/1.1\r\nHost: pc:8631\r\nContent-Length: 42\r\n\r\n"))!!
        assertEquals("POST /ipp/print HTTP/1.1", head.startLine)
        assertEquals("pc:8631", head.get("host"))          // case-insensitive lookup
        assertEquals("42", head.get("Content-Length"))
    }

    @Test
    fun `returns null on immediate EOF`() {
        assertNull(HttpHead.parse(stream("")))
    }

    @Test
    fun `set replaces header case-insensitively`() {
        val head = HttpHead.parse(stream("GET / HTTP/1.1\r\nHOST: a\r\n\r\n"))!!
        head.set("Host", "localhost")
        assertEquals("localhost", head.get("host"))
        val text = String(head.serialize(), Charsets.ISO_8859_1)
        assertEquals(1, Regex("(?im)^host:").findAll(text).count())
    }

    @Test
    fun `serialize round-trips`() {
        val original = "POST /ipp/print HTTP/1.1\r\nHost: x\r\nContent-Type: application/ipp\r\n\r\n"
        val head = HttpHead.parse(stream(original))!!
        assertEquals(original, String(head.serialize(), Charsets.ISO_8859_1))
    }
}
