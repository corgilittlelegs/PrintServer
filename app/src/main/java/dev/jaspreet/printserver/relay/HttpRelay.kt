package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.http.BodyCopier
import dev.jaspreet.printserver.http.BodyReader
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.usb.UsbTransport
import dev.jaspreet.printserver.usb.UsbTransportInputStream
import dev.jaspreet.printserver.usb.UsbTransportOutputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object HttpRelay {
    /** Same cap as an incoming print document — a well-behaved IPP-USB printer's
     *  response (status + attributes, no document body) is tiny; anything near
     *  this size means a misbehaving printer, not a legitimate reply. */
    private const val MAX_RESPONSE_BYTES = BodyReader.DEFAULT_MAX_BYTES

    /** Bounded sink: throws once the printer's response exceeds [MAX_RESPONSE_BYTES],
     *  instead of buffering an unbounded amount of memory. */
    private class BoundedByteArrayOutputStream(private val limit: Long) : ByteArrayOutputStream() {
        private var total = 0L
        override fun write(b: Int) { total++; check(); super.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { total += len; check(); super.write(b, off, len) }
        private fun check() { if (total > limit) throw IOException("Printer response exceeded $limit bytes") }
    }

    /**
     * Forwards one already-parsed HTTP request ([head] + remaining body on
     * [clientIn]) over [usb] and streams the printer's response to [clientOut].
     * The caller owns channel lease/release/discard.
     *
     * Once the printer's response has been fully read off USB, the transaction
     * is complete from the printer's point of view — a failure delivering that
     * response to [clientOut] (client vanished, socket reset) is not a channel
     * fault and must not cause the caller to discard the USB channel.
     */
    fun forward(head: HttpHead, clientIn: InputStream, clientOut: OutputStream, usb: UsbTransport) {
        val usbOut = UsbTransportOutputStream(usb)
        val usbIn = BufferedInputStream(UsbTransportInputStream(usb))

        // We always read the full body before writing anything to the printer, so
        // there is never a reason to withhold it — tell the client to send now, and
        // strip the header before relaying so the printer never has to run its own
        // 100-continue handshake with us mid-transaction.
        if (head.get("Expect")?.contains("100-continue", ignoreCase = true) == true) {
            clientOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            clientOut.flush()
            head.remove("Expect")
        }

        head.set("Host", "localhost")   // some printer firmware rejects unknown hosts
        usbOut.write(head.serialize())
        BodyCopier.copy(head, clientIn, usbOut)
        usbOut.flush()

        val respHead = HttpHead.parse(usbIn) ?: throw IOException("Printer closed channel without response")
        val respBody = BoundedByteArrayOutputStream(MAX_RESPONSE_BYTES)
        BodyCopier.copy(respHead, usbIn, respBody)

        try {
            clientOut.write(respHead.serialize())
            respBody.writeTo(clientOut)
            clientOut.flush()
        } catch (_: IOException) {
            // Printer already handled the request; the client just isn't there to hear back.
        }
    }
}
