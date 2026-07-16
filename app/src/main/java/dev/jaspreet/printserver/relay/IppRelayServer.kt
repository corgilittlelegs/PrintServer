package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.http.HttpHead
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
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
                val channel = pool.lease()
                monitor.begin()
                try {
                    HttpRelay.forward(head, cin, cout, channel)
                    pool.release(channel)
                } catch (e: Exception) {
                    // Channel state unknown mid-transaction: never reuse it.
                    pool.discard(channel)
                    break
                } finally {
                    monitor.end()
                }
                if (head.get("Connection")?.equals("close", ignoreCase = true) == true) break
            }
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }
}
