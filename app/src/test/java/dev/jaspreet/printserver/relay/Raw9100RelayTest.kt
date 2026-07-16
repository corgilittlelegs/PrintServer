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

    @Test
    fun `stop closes the connected client socket instead of leaving it to linger`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val r = Raw9100Relay(port = 0) { printer }
        r.start(bindAddress = null)
        relay = r

        // Open a client connection but never send or close anything, so the
        // relay's handler thread is parked in a blocking Socket.read().
        val client = Socket("127.0.0.1", r.actualPort)
        // Give the accept loop time to dispatch the connection to handle().
        Thread.sleep(200)

        try {
            r.stop()

            // If stop() closed the server-side socket for this connection,
            // the client's read() should observe EOF (-1) promptly. If the
            // connection was left open (the bug), this read blocks and the
            // soTimeout below fires instead.
            client.soTimeout = 2000
            val result = client.getInputStream().read()
            assertEquals(-1, result)
        } finally {
            client.close()
        }
    }
}
