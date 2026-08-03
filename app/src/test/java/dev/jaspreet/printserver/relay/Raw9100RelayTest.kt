package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.access.ClientAccessGate
import dev.jaspreet.printserver.access.NetworkService
import dev.jaspreet.printserver.usb.FakePrinterTransport
import dev.jaspreet.printserver.usb.LegacyPrinterSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Raw9100RelayTest {

    private var relay: Raw9100Relay? = null

    @After
    fun tearDown() { relay?.stop() }

    @Test
    fun `pipes client bytes verbatim to the printer`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val r = Raw9100Relay(port = 0, LegacyPrinterSession { printer })
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
    fun `restricted raw client is closed before bytes reach the printer`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        var observedService: NetworkService? = null
        val r = Raw9100Relay(
            port = 0,
            legacySession = LegacyPrinterSession { printer },
            clientAccessGate = ClientAccessGate { _, service ->
                observedService = service
                false
            },
        )
        r.start(bindAddress = null)
        relay = r

        Socket("127.0.0.1", r.actualPort).use { socket ->
            socket.soTimeout = 2_000
            val closed = try {
                socket.getOutputStream().write("BLOCKED".toByteArray())
                socket.getInputStream().read() == -1
            } catch (_: java.net.SocketException) {
                true
            }
            assertTrue("expected blocked raw connection to close", closed)
        }
        assertEquals(NetworkService.RAW_PRINT, observedService)
        assertEquals(0, printer.lastRequest().size)
    }

    @Test
    fun `stop closes the connected client socket instead of leaving it to linger`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val r = Raw9100Relay(port = 0, LegacyPrinterSession { printer })
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

    @Test
    fun `raw client connection is rejected when an active print job holds the legacy transport lock`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val session = LegacyPrinterSession { printer }

        // Simulate a Tier 2 print job mid-write: hold the shared session's lock the same
        // way JobQueue.writeToUsb does, for long enough that the raw relay's short
        // reject-timeout below is guaranteed to expire while the job is still writing.
        val jobEntered = CountDownLatch(1)
        val releaseJob = CountDownLatch(1)
        val jobThread = Thread {
            session.writeExclusive("simulated-print-job") {
                jobEntered.countDown()
                // Outlasts LegacyPrinterSession.DEFAULT_TIMEOUT_MS so the raw side's
                // tryWriteExclusive is guaranteed to time out and reject while this job
                // still holds the lock, rather than racing its own release.
                releaseJob.await(10, TimeUnit.SECONDS)
            }
        }
        jobThread.start()
        assertTrue(jobEntered.await(5, TimeUnit.SECONDS))

        val r = Raw9100Relay(port = 0, session)
        r.start(bindAddress = null)
        relay = r

        val client = Socket("127.0.0.1", r.actualPort)
        client.getOutputStream().write("SHOULD NOT REACH PRINTER".toByteArray())
        // Comfortably longer than LegacyPrinterSession.DEFAULT_TIMEOUT_MS (the raw side's
        // reject timeout) so this assertion isn't racing the server's own timeout.
        client.soTimeout = 8_000
        // The relay must close this connection (not hang forever, not write through) once
        // it fails to acquire the lock within its short timeout. The client observes either
        // EOF (-1) or a reset, depending on OS-level TCP behavior — since the server-side
        // close() happens with the client's already-written bytes still unread in its
        // receive buffer, some platforms send RST instead of a clean FIN. Either is
        // conclusive proof the relay tore the connection down without ever reading/writing.
        val observedRejection = try {
            client.getInputStream().read() == -1
        } catch (_: java.net.SocketException) {
            true
        }
        assertTrue("expected the relay to close/reset the rejected connection", observedRejection)
        client.close()

        releaseJob.countDown()
        jobThread.join(5_000)

        // The rejected raw client's bytes must never have reached the printer.
        assertEquals("", String(printer.lastRequest()))
    }

    @Test
    fun `slow raw client is closed after the total session time cap`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val r = Raw9100Relay(
            port = 0,
            legacySession = LegacyPrinterSession { printer },
            maxSessionMs = 200,
        )
        r.start(bindAddress = null)
        relay = r

        val writeFailed = AtomicBoolean(false)
        Socket("127.0.0.1", r.actualPort).use { socket ->
            socket.soTimeout = 2_000
            val writer = Thread {
                try {
                    repeat(20) {
                        socket.getOutputStream().write('x'.code)
                        socket.getOutputStream().flush()
                        Thread.sleep(75)
                    }
                } catch (_: Exception) {
                    writeFailed.set(true)
                }
            }
            writer.start()

            val observedClose = try {
                socket.getInputStream().read() == -1
            } catch (_: java.net.SocketException) {
                true
            }

            writer.join(2_000)
            assertTrue("expected the relay to close/reset the capped raw session", observedClose || writeFailed.get())
            assertTrue("expected only a prefix of the slow stream to reach the printer", printer.lastRequest().size < 20)
        }
    }
}
