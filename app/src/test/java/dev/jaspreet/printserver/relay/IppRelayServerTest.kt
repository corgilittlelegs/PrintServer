package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.activity.ActivityLog
import dev.jaspreet.printserver.activity.ActivityStatus
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class IppRelayServerTest {

    private var server: IppRelayServer? = null

    @Before
    fun resetActivityLog() {
        ActivityLog.clear()
    }

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

    @Test
    fun `records a PRINTED activity entry for a Print-Job request`() {
        val port = startServer()
        // IPP packet header: version 1.1, operation-id 0x0002 (Print-Job), request-id 1,
        // followed by end-of-attributes-tag (0x03) — a minimal-but-parseable-length packet.
        // IppRelayServer only reads the first 4 bytes; it never decodes this as IPP.
        val body = byteArrayOf(0x01, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x01, 0x03)
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/ipp")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        assertEquals(200, conn.responseCode)
        conn.inputStream.readBytes()

        // ActivityLog.update() to PRINTED runs on the server's handler thread just
        // after the response bytes are flushed to the client socket; there is no
        // happens-before edge guaranteeing this test thread observes it the instant
        // the HTTP call returns, so poll briefly instead of asserting immediately.
        val entries = awaitSettledEntries()
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].tier)
        assertEquals("Print request", entries[0].name)
        assertEquals(ActivityStatus.PRINTED, entries[0].status)
        assertEquals(body.size.toLong(), entries[0].sizeBytes)
        assertEquals("127.0.0.1", entries[0].clientAddress)
    }

    @Test
    fun `does not record an entry for a non-print IPP operation`() {
        val port = startServer()
        // operation-id 0x000B = Get-Printer-Attributes.
        val body = byteArrayOf(0x01, 0x01, 0x00, 0x0B, 0x00, 0x00, 0x00, 0x01, 0x03)
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/ipp")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        assertEquals(200, conn.responseCode)
        conn.inputStream.readBytes()

        assertTrue(ActivityLog.entries.value.isEmpty())
    }

    @Test
    fun `does not record an entry for a non-IPP request`() {
        val port = startServer()
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(3)
        conn.outputStream.use { it.write("abc".toByteArray()) }
        assertEquals(200, conn.responseCode)
        conn.inputStream.readBytes()

        assertTrue(ActivityLog.entries.value.isEmpty())
    }

    @Test
    fun `peeking the operation id does not alter bytes forwarded to the printer`() {
        val body = byteArrayOf(0x01, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x01, 0x03, 0x41, 0x42)
        val printer = FakePrinterTransport { req ->
            // The printer receives the whole HTTP request (header + body) — HttpURLConnection
            // adds its own headers (User-Agent, Accept, Connection, ...) on top of the ones
            // this test sets, so the byte-preservation check below recovers just the body
            // length rather than assuming the total equals body.size.
            val bodyLen = req.size - indexOfHeaderEnd(req)
            val len = "len=$bodyLen"
            ("HTTP/1.1 200 OK\r\nContent-Length: ${len.length}\r\n\r\n$len").toByteArray(Charsets.ISO_8859_1)
        }
        val s = IppRelayServer(port = 0, pool = ChannelPool(listOf(printer)))
        s.start(bindAddress = null)
        server = s

        val conn = URL("http://127.0.0.1:${s.actualPort}/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/ipp")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        assertEquals(200, conn.responseCode)
        val respBody = conn.inputStream.readBytes().toString(Charsets.ISO_8859_1)

        // The printer must have received exactly body.size body bytes — proving the 4 peeked
        // bytes were re-prepended, not dropped.
        assertEquals("len=${body.size}", respBody)
    }

    /** Index just past the first bare-CRLF-CRLF (HTTP header/body boundary) in [bytes]. */
    private fun indexOfHeaderEnd(bytes: ByteArray): Int {
        val marker = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)
        outer@ for (i in 0..bytes.size - marker.size) {
            for (j in marker.indices) if (bytes[i + j] != marker[j]) continue@outer
            return i + marker.size
        }
        throw IllegalStateException("no header/body boundary found in fake printer request")
    }

    /**
     * Polls [ActivityLog.entries] until no entry is still in PRINTING state (or a
     * timeout elapses), since the log update for the terminal status happens on a
     * different thread than the one observing the HTTP response.
     */
    private fun awaitSettledEntries(timeoutMs: Long = 2000): List<dev.jaspreet.printserver.activity.ActivityEntry> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val entries = ActivityLog.entries.value
            if (entries.isNotEmpty() && entries.none { it.status == ActivityStatus.PRINTING }) return entries
            Thread.sleep(5)
        }
        return ActivityLog.entries.value
    }
}
