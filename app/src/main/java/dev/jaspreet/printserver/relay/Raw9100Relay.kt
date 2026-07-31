package dev.jaspreet.printserver.relay

import android.util.Log
import dev.jaspreet.printserver.usb.LegacyPrinterSession
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
    /** Shared with JobQueue so a raw client's writes can never interleave with an
     *  in-progress Tier 2 rendered print job's writes — see [LegacyPrinterSession]. */
    private val legacySession: LegacyPrinterSession,
    private val maxSessionMs: Long = DEFAULT_MAX_SESSION_MS,
    private val maxSessionBytes: Long = DEFAULT_MAX_SESSION_BYTES,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var currentClient: Socket? = null
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
        currentClient = client
        client.soTimeout = 60_000
        // Acquired once for the whole connection (not per chunk): a raw-9100 session is one
        // logical print stream from the printer's point of view, so once it wins the lock it
        // holds the transport exclusively until the client disconnects — a JobQueue job must
        // not be able to squeeze a chunk in between two of this client's writes either.
        // If a Tier 2 job is already mid-write, wait briefly rather than corrupt the stream
        // by writing concurrently, and give up (closing the connection) if it doesn't free up.
        val result = legacySession.tryWriteExclusive("raw-9100 client (${client.inetAddress?.hostAddress})") { transport ->
            val buf = ByteArray(65536)
            val input = client.getInputStream()
            val startedAt = System.currentTimeMillis()
            var totalBytes = 0L
            try {
                while (true) {
                    if (System.currentTimeMillis() - startedAt > maxSessionMs) {
                        Log.w(TAG, "raw 9100 client exceeded ${maxSessionMs}ms session cap, closing")
                        break
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    totalBytes += n
                    if (totalBytes > maxSessionBytes) {
                        Log.w(TAG, "raw 9100 client exceeded ${maxSessionBytes} byte session cap, closing")
                        break
                    }
                    transport.write(buf, 0, n)
                }
            } catch (_: IOException) {
                // client gone or printer stalled; drop the connection
            }
        }
        if (result == null) {
            Log.w(
                TAG,
                "raw 9100 client connection rejected: legacy printer transport busy with an active print job",
            )
            try { client.close() } catch (_: IOException) {}
        }
        currentClient = null
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        try { currentClient?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "Raw9100Relay"
        private const val DEFAULT_MAX_SESSION_MS = 10L * 60L * 1000L
        private const val DEFAULT_MAX_SESSION_BYTES = 200L * 1_000_000L
    }
}
