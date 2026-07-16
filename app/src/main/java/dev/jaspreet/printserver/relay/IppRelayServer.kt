package dev.jaspreet.printserver.relay

import android.util.Log
import dev.jaspreet.printserver.http.HttpHead
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
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
                monitor.begin()
                try {
                    HttpRelay.forward(head, cin, cout, channel)
                    pool.release(channel)
                } catch (e: Exception) {
                    // Channel state unknown mid-transaction: never reuse it.
                    Log.w(TAG, "discarding channel after transaction failure", e)
                    pool.discard(channel)
                    break
                } finally {
                    monitor.end()
                }
                if (head.get("Connection")?.equals("close", ignoreCase = true) == true) break
            }
        }
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
    }
}
