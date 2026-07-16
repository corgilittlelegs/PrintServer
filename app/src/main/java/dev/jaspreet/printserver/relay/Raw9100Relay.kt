package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.UsbTransport
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * JetDirect/AppSocket fallback for non-IPP-USB printers: one client at a
 * time, bytes piped verbatim to the printer's bulk OUT. The client must
 * have the printer's driver installed — this path does no translation.
 */
class Raw9100Relay(
    private val port: Int,
    private val transportProvider: () -> UsbTransport,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor()

    val actualPort: Int get() = serverSocket?.localPort ?: port

    fun start(bindAddress: InetAddress?) {
        val ss = ServerSocket(port, 1, bindAddress)
        serverSocket = ss
        executor.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                client.use { handle(it) }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 60_000
        val transport = transportProvider()
        val buf = ByteArray(65536)
        val input = client.getInputStream()
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                transport.write(buf, 0, n)
            }
        } catch (_: IOException) {
            // client gone or printer stalled; drop the connection
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }
}
