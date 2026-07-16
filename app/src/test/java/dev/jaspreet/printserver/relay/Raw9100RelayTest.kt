package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Socket

class Raw9100RelayTest {

    private var relay: Raw9100Relay? = null

    @After
    fun tearDown() { relay?.stop() }

    @Test
    fun `pipes client bytes verbatim to the printer`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val r = Raw9100Relay(port = 0) { printer }
        r.start(bindAddress = null)
        relay = r

        Socket("127.0.0.1", r.actualPort).use { socket ->
            socket.getOutputStream().write("RAW PCL BYTES".toByteArray())
            socket.shutdownOutput()
            // wait for the relay to drain the socket
            Thread.sleep(300)
        }
        assertEquals("RAW PCL BYTES", String(printer.lastRequest()))
    }
}
