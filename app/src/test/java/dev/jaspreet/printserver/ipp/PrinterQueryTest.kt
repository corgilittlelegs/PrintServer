package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import dev.jaspreet.printserver.relay.ChannelPool
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI

class PrinterQueryTest {

    private fun ippResponseBytes(): ByteArray {
        val packet = IppPacket(
            Status.successfulOk, 1,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
            ),
            groupOf(
                Tag.printerAttributes,
                Types.printerMakeAndModel.of("Test Laser 9000"),
                Types.documentFormatSupported.of("application/pdf", "image/pwg-raster"),
                Types.colorSupported.of(true),
                Types.printerUuid.of(URI.create("urn:uuid:11111111-2222-3333-4444-555555555555")),
            ),
        )
        val ipp = ByteArrayOutputStream()
        IppOutputStream(ipp).write(packet)
        val body = ipp.toByteArray()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: ${body.size}\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    @Test
    fun `queries and parses printer attributes over a channel`() {
        val printer = FakePrinterTransport { ippResponseBytes() }
        val info = PrinterQuery.getAttributes(ChannelPool(listOf(printer)))
        assertEquals("Test Laser 9000", info.makeAndModel)
        assertEquals(listOf("application/pdf", "image/pwg-raster"), info.formats)
        assertTrue(info.color)
        assertEquals("11111111-2222-3333-4444-555555555555", info.uuid)
        // and the request we sent was an HTTP POST carrying IPP
        val sent = String(printer.lastRequest(), Charsets.ISO_8859_1)
        assertTrue(sent.startsWith("POST /ipp/print HTTP/1.1\r\n"))
        assertTrue(sent.contains("Content-Type: application/ipp"))
    }

    private fun malformedIppResponseBytes(): ByteArray {
        // Valid IPP framing, but no printer-attributes group at all — parse() must reject it.
        val packet = IppPacket(
            Status.successfulOk, 1,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
            ),
        )
        val ipp = ByteArrayOutputStream()
        IppOutputStream(ipp).write(packet)
        val body = ipp.toByteArray()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: ${body.size}\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    @Test
    fun `discards rather than releases the channel when the response is malformed`() {
        val printer = FakePrinterTransport { malformedIppResponseBytes() }
        val pool = ChannelPool(listOf(printer))
        try {
            PrinterQuery.getAttributes(pool)
            fail("Expected getAttributes to throw on a malformed IPP response")
        } catch (_: Exception) {
            // expected
        }
        assertTrue("Channel should have been discarded (closed), not released", printer.closed)
    }
}
