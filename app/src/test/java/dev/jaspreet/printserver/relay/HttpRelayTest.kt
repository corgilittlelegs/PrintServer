package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

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

    @Test
    fun `client write failure after printer responded does not throw`() {
        val printer = FakePrinterTransport { cannedResponse }
        val request = "POST /ipp/print HTTP/1.1\r\nHost: phone.lan:8631\r\nContent-Length: 5\r\n\r\nhello"
        val clientIn = ByteArrayInputStream(request.toByteArray(Charsets.ISO_8859_1))
        val deadClientOut = object : OutputStream() {
            override fun write(b: Int) = throw IOException("client vanished")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("client vanished")
        }

        val head = HttpHead.parse(clientIn)!!
        // Must complete normally: the printer already got and answered the request,
        // so a dead client socket is not a channel fault.
        HttpRelay.forward(head, clientIn, deadClientOut, printer)
    }

    @Test
    fun `answers 100-continue itself and never forwards Expect to the printer`() {
        val printer = FakePrinterTransport { cannedResponse }
        val request = "POST /ipp/print HTTP/1.1\r\nHost: phone.lan:8631\r\n" +
            "Expect: 100-continue\r\nContent-Length: 5\r\n\r\nhello"
        val clientIn = ByteArrayInputStream(request.toByteArray(Charsets.ISO_8859_1))
        val clientOut = ByteArrayOutputStream()

        val head = HttpHead.parse(clientIn)!!
        HttpRelay.forward(head, clientIn, clientOut, printer)

        val seenByClient = clientOut.toString("ISO-8859-1")
        assertTrue("Client must get the interim 100-continue first",
            seenByClient.startsWith("HTTP/1.1 100 Continue\r\n\r\n"))
        assertTrue("Final printer response must still follow",
            seenByClient.endsWith(String(cannedResponse, Charsets.ISO_8859_1)))

        val sentToPrinter = String(printer.lastRequest(), Charsets.ISO_8859_1)
        assertTrue("Body must still be forwarded", sentToPrinter.endsWith("hello"))
        assertTrue("Expect header must not reach the printer",
            !sentToPrinter.contains("Expect", ignoreCase = true))
    }
}
