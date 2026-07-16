package dev.jaspreet.printserver.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

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

    @Test
    fun `throws on line exceeding max length`() {
        val tooLong = "GET /" + "a".repeat(9000) + " HTTP/1.1\r\nHost: x\r\n\r\n"
        assertThrows(IOException::class.java) {
            HttpHead.parse(stream(tooLong))
        }
    }

    @Test
    fun `throws on too many headers`() {
        val sb = StringBuilder("GET / HTTP/1.1\r\n")
        repeat(101) { sb.append("X-Header-$it: v\r\n") }
        sb.append("\r\n")
        assertThrows(IOException::class.java) {
            HttpHead.parse(stream(sb.toString()))
        }
    }

    @Test
    fun `set rejects value containing CRLF`() {
        val head = HttpHead.parse(stream("GET / HTTP/1.1\r\nHost: x\r\n\r\n"))!!
        assertThrows(IllegalArgumentException::class.java) {
            head.set("Host", "evil\r\nX-Injected: yes")
        }
    }
}
