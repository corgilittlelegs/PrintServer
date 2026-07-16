package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
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

    @Test
    fun `channel that fails the transaction is discarded, not reused`() {
        val failing = FakePrinterTransport { throw IOException("boom") }
        val ok = FakePrinterTransport { req ->
            val body = "len=${req.size}"
            ("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body")
                .toByteArray(Charsets.ISO_8859_1)
        }
        val pool = ChannelPool(listOf(failing, ok))
        val s = IppRelayServer(port = 0, pool = pool)
        s.start(bindAddress = null)
        server = s

        // First request lands on the failing channel; the connection dies
        // without a response since the failure surfaces mid-transaction.
        val conn1 = URL("http://127.0.0.1:${s.actualPort}/ipp/print").openConnection() as HttpURLConnection
        conn1.requestMethod = "POST"
        conn1.doOutput = true
        conn1.setFixedLengthStreamingMode(1)
        conn1.outputStream.use { it.write("x".toByteArray()) }
        try {
            conn1.responseCode
        } catch (_: IOException) {
            // Expected: server closed the connection after discarding the channel.
        }

        assertTrue("failing channel should have been closed by discard()", failing.closed)

        // Second request on a fresh connection must succeed via the surviving
        // channel — proving the failing one was removed from the pool, not reused.
        val conn2 = URL("http://127.0.0.1:${s.actualPort}/ipp/print").openConnection() as HttpURLConnection
        conn2.requestMethod = "POST"
        conn2.doOutput = true
        conn2.setFixedLengthStreamingMode(1)
        conn2.outputStream.use { it.write("y".toByteArray()) }
        assertEquals(200, conn2.responseCode)
        conn2.inputStream.readBytes()
    }

    @Test
    fun `rejects a request with 503 when no channel is free within the lease timeout`() {
        val printer = FakePrinterTransport { req ->
            val body = "len=${req.size}"
            ("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body")
                .toByteArray(Charsets.ISO_8859_1)
        }
        val pool = ChannelPool(listOf(printer))
        // Occupy the only channel for the duration of this test so the server's
        // lease() call has nothing to grab and must time out.
        val heldChannel = pool.lease()

        val s = IppRelayServer(port = 0, pool = pool, leaseTimeoutMs = 200)
        s.start(bindAddress = null)
        server = s

        val conn = URL("http://127.0.0.1:${s.actualPort}/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(1)
        conn.outputStream.use { it.write("x".toByteArray()) }
        assertEquals(503, conn.responseCode)

        pool.release(heldChannel)
    }
}
