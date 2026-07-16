package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.http.BodyCopier
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.usb.UsbTransport
import dev.jaspreet.printserver.usb.UsbTransportInputStream
import dev.jaspreet.printserver.usb.UsbTransportOutputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object HttpRelay {
    /**
     * Forwards one already-parsed HTTP request ([head] + remaining body on
     * [clientIn]) over [usb] and streams the printer's response to [clientOut].
     * The caller owns channel lease/release/discard.
     */
    fun forward(head: HttpHead, clientIn: InputStream, clientOut: OutputStream, usb: UsbTransport) {
        val usbOut = UsbTransportOutputStream(usb)
        val usbIn = BufferedInputStream(UsbTransportInputStream(usb))

        head.set("Host", "localhost")   // some printer firmware rejects unknown hosts
        usbOut.write(head.serialize())
        BodyCopier.copy(head, clientIn, usbOut)
        usbOut.flush()

        val respHead = HttpHead.parse(usbIn) ?: throw IOException("Printer closed channel without response")
        clientOut.write(respHead.serialize())
        BodyCopier.copy(respHead, usbIn, clientOut)
        clientOut.flush()
    }
}
