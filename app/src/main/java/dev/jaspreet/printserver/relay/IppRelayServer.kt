package dev.jaspreet.printserver.relay

import android.util.Log
import dev.jaspreet.printserver.activity.ActivityLog
import dev.jaspreet.printserver.activity.ActivityStatus
import dev.jaspreet.printserver.http.HttpHead
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Accepts LAN HTTP connections and relays each transaction over a pooled
 * IPP-USB channel. Thread-per-connection: both socket and USB I/O block.
 */
class IppRelayServer(
    private val port: Int,
    private val pool: ChannelPool,
    private val monitor: ActivityMonitor = ActivityMonitor.NONE,
    private val leaseTimeoutMs: Long = 60_000,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    val actualPort: Int get() = serverSocket?.localPort ?: port

    fun start(bindAddress: InetAddress?) {
        val ss = ServerSocket(port, 50, bindAddress)
        serverSocket = ss
        executor.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                executor.execute { handleClient(client) }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 30_000
        val clientAddress = client.inetAddress?.hostAddress
        client.use {
            val cin = BufferedInputStream(client.getInputStream())
            val cout = BufferedOutputStream(client.getOutputStream())
            while (true) {
                // Parse the head BEFORE leasing, so an idle keep-alive
                // connection never pins a printer channel.
                val head = try { HttpHead.parse(cin) ?: break } catch (_: SocketTimeoutException) { break } catch (_: IOException) { break }
                val channel = try {
                    pool.lease(leaseTimeoutMs)
                } catch (e: Exception) {
                    Log.w(TAG, "no free printer channel available, rejecting request", e)
                    writeServiceUnavailable(cout)
                    break
                }

                var relayInput: InputStream = cin
                var activityId: Int? = null
                val isIpp = head.get("Content-Type")?.startsWith("application/ipp", ignoreCase = true) == true
                if (isIpp) {
                    val (peeked, opId) = peekIppOperation(cin)
                    relayInput = peeked
                    if (opId != null && opId in PRINT_OPERATIONS) {
                        activityId = ActivityLog.record(
                            tier = 1, name = "Print request", status = ActivityStatus.PRINTING,
                            clientAddress = clientAddress,
                            sizeBytes = head.get("Content-Length")?.toLongOrNull(),
                        )
                    }
                }

                monitor.begin()
                try {
                    HttpRelay.forward(head, relayInput, cout, channel)
                    pool.release(channel)
                    activityId?.let { id ->
                        ActivityLog.update(id) { it.copy(status = ActivityStatus.PRINTED, completedAt = System.currentTimeMillis()) }
                    }
                } catch (e: Exception) {
                    // Channel state unknown mid-transaction: never reuse it.
                    Log.w(TAG, "discarding channel after transaction failure", e)
                    pool.discard(channel)
                    activityId?.let { id ->
                        ActivityLog.update(id) { it.copy(status = ActivityStatus.FAILED, completedAt = System.currentTimeMillis(), failureReason = e.message) }
                    }
                    break
                } finally {
                    monitor.end()
                }
                if (head.get("Connection")?.equals("close", ignoreCase = true) == true) break
            }
        }
    }

    /**
     * Reads up to the first 4 bytes of an IPP request (version-major, version-minor,
     * operation-id) without touching anything beyond that — no attribute-group or
     * document parsing. Always returns a stream that reproduces the original byte
     * sequence exactly (the peeked bytes are re-prepended via SequenceInputStream),
     * so HttpRelay.forward's zero-buffering behavior is unaffected. Returns a null
     * operation-id if fewer than 4 bytes were available (malformed/short request —
     * let HttpRelay/the printer surface that error naturally).
     */
    private fun peekIppOperation(cin: InputStream): Pair<InputStream, Int?> {
        val peek = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = cin.read(peek, read, 4 - read)
            if (n < 0) break
            read += n
        }
        val combined = SequenceInputStream(ByteArrayInputStream(peek, 0, read), cin)
        val opId = if (read == 4) ((peek[2].toInt() and 0xFF) shl 8) or (peek[3].toInt() and 0xFF) else null
        return combined to opId
    }

    private fun writeServiceUnavailable(cout: OutputStream) {
        try {
            cout.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            cout.flush()
        } catch (_: IOException) {
            // Client already gone; nothing more we can do.
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "IppRelayServer"

        // IPP operation-ids that initiate a print (Print-Job, Create-Job, Send-Document).
        // Everything else (Get-Printer-Attributes, Validate-Job, Cancel-Job, ...) stays silent.
        val PRINT_OPERATIONS = setOf(0x0002, 0x0005, 0x0006)
    }
}
