package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpRelayTest {

    private val cannedResponse =
        "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: 4\r\n\r\nDONE"
            .toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `forwards request with rewritten host and returns printer response`() {
        val printer = FakePrinterTransport { cannedResponse }
        val request = "POST /ipp/print HTTP/1.1\r\nHost: phone.lan:8631\r\nContent-Length: 5\r\n\r\nhello"
        val clientIn = ByteArrayInputStream(request.toByteArray(Charsets.ISO_8859_1))
        val clientOut = ByteArrayOutputStream()

        val head = HttpHead.parse(clientIn)!!
        HttpRelay.forward(head, clientIn, clientOut, printer)

        val sent = String(printer.lastRequest(), Charsets.ISO_8859_1)
        assertTrue("Host must be rewritten", sent.contains("Host: localhost\r\n"))
        assertTrue("Body must be forwarded", sent.endsWith("hello"))
        assertEquals(String(cannedResponse, Charsets.ISO_8859_1), clientOut.toString("ISO-8859-1"))
    }
}
