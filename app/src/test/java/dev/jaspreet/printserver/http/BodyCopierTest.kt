package dev.jaspreet.printserver.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class BodyCopierTest {

    private fun head(vararg headers: Pair<String, String>) =
        HttpHead("POST / HTTP/1.1", headers.toList())

    private fun copy(head: HttpHead, body: String): Pair<String, String> {
        val input = ByteArrayInputStream((body + "LEFTOVER").toByteArray(Charsets.ISO_8859_1))
        val out = ByteArrayOutputStream()
        BodyCopier.copy(head, input, out)
        val remaining = input.readBytes().toString(Charsets.ISO_8859_1)
        return out.toString("ISO-8859-1") to remaining
    }

    @Test
    fun `copies exactly content-length bytes`() {
        val (copied, remaining) = copy(head("Content-Length" to "5"), "hello")
        assertEquals("hello", copied)
        assertEquals("LEFTOVER", remaining)  // did not over-read
    }

    @Test
    fun `no framing headers means no body`() {
        val (copied, remaining) = copy(head(), "")
        assertEquals("", copied)
        assertEquals("LEFTOVER", remaining)
    }

    @Test
    fun `copies chunked body verbatim including terminator`() {
        val chunked = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        val (copied, remaining) = copy(head("Transfer-Encoding" to "chunked"), chunked)
        assertEquals(chunked, copied)
        assertEquals("LEFTOVER", remaining)
    }

    @Test
    fun `malformed content-length throws`() {
        assertThrows(IOException::class.java) {
            copy(head("Content-Length" to "abc"), "hello")
        }
    }

    @Test
    fun `negative content-length throws`() {
        assertThrows(IOException::class.java) {
            copy(head("Content-Length" to "-5"), "hello")
        }
    }

    @Test
    fun `content-length and transfer-encoding both present throws`() {
        assertThrows(IOException::class.java) {
            copy(head("Content-Length" to "5", "Transfer-Encoding" to "chunked"), "helloLEFTOVER")
        }
    }

    @Test
    fun `duplicate content-length with differing values throws`() {
        assertThrows(IOException::class.java) {
            copy(head("Content-Length" to "5", "Content-Length" to "10"), "hello")
        }
    }
}
