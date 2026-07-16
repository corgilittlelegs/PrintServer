package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class IppRelayServerTest {

    private var server: IppRelayServer? = null

    @After
    fun tearDown() { server?.stop() }

    private fun startServer(): Int {
        val printer = FakePrinterTransport { req ->
            // echo the request body length back
            val body = "len=${req.size}"
            ("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body")
                .toByteArray(Charsets.ISO_8859_1)
        }
        val s = IppRelayServer(port = 0, pool = ChannelPool(listOf(printer)))
        s.start(bindAddress = null)
        server = s
        return s.actualPort
    }

    @Test
    fun `relays a POST end to end over real sockets`() {
        val port = startServer()
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(3)
        conn.outputStream.use { it.write("abc".toByteArray()) }
        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.readBytes().toString(Charsets.ISO_8859_1)
        assertEquals(true, body.startsWith("len="))
    }

    @Test
    fun `serves two sequential requests on keep-alive connections`() {
        val port = startServer()
        repeat(2) {
            val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(1)
            conn.outputStream.use { it.write("x".toByteArray()) }
            assertEquals(200, conn.responseCode)
            conn.inputStream.readBytes()
        }
    }
}
