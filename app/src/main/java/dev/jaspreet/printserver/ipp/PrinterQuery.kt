package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Types
import dev.jaspreet.printserver.http.BodyCopier
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.relay.ChannelPool
import dev.jaspreet.printserver.usb.UsbTransportInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI

/** One-time Get-Printer-Attributes over an IPP-USB channel; feeds mDNS TXT and the UI. */
object PrinterQuery {

    fun getAttributes(pool: ChannelPool): PrinterInfo {
        val ippBody = buildRequestBytes()
        val http = ("POST /ipp/print HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/ipp\r\n" +
                "Content-Length: ${ippBody.size}\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1)

        val channel = pool.lease()
        try {
            channel.write(http, 0, http.size)
            channel.write(ippBody, 0, ippBody.size)
            val input = BufferedInputStream(UsbTransportInputStream(channel))
            val head = HttpHead.parse(input) ?: throw IOException("No response to attribute query")
            val body = ByteArrayOutputStream()
            BodyCopier.copy(head, input, body)
            val packet = IppInputStream(ByteArrayInputStream(body.toByteArray())).readPacket()
            val info = parse(packet)
            pool.release(channel)
            return info
        } catch (e: Exception) {
            pool.discard(channel)
            throw e
        }
    }

    private fun buildRequestBytes(): ByteArray {
        val packet = IppPacket(
            Operation.getPrinterAttributes, 1,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://localhost/ipp/print")),
                Types.requestedAttributes.of(
                    "printer-make-and-model",
                    "document-format-supported",
                    "color-supported",
                    "printer-uuid",
                    "urf-supported",
                ),
            ),
        )
        val out = ByteArrayOutputStream()
        IppOutputStream(out).write(packet)
        return out.toByteArray()
    }

    private fun parse(packet: IppPacket): PrinterInfo {
        val group = packet.get(Tag.printerAttributes)
            ?: throw IOException("Response has no printer-attributes group")
        val make = group.getValue(Types.printerMakeAndModel)?.value ?: "USB Printer"
        val formats = group.getValues(Types.documentFormatSupported)
        val color = group.getValue(Types.colorSupported) ?: false
        val uuid = group.getValue(Types.printerUuid)?.toString()?.removePrefix("urn:uuid:")
        // urf-supported has no typed constant in JIPP's model; read it by raw name.
        val urf = group.get("urf-supported")?.strings() ?: emptyList()
        return PrinterInfo(make, formats, color, uuid, urf)
    }
}
