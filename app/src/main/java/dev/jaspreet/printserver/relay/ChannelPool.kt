package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.UsbTransport
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pool of exclusive IPP-USB channels. IPP-USB rule: one complete HTTP
 * request/response per channel at a time. Lease before forwarding a
 * transaction; release only after the response fully streamed; discard
 * (never release) a channel that may hold a half-finished transaction.
 */
class ChannelPool(transports: List<UsbTransport>) {
    private val queue = ArrayBlockingQueue<UsbTransport>(maxOf(transports.size, 1))
    private val alive = AtomicInteger(transports.size)

    /** Invoked once when the last channel is discarded (printer needs reconnect). */
    var onAllChannelsDead: () -> Unit = {}

    init {
        transports.forEach { queue.put(it) }
    }

    fun lease(timeoutMs: Long = 60_000): UsbTransport =
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw IOException("Timed out waiting for a free printer channel")

    fun release(transport: UsbTransport) {
        queue.put(transport)
    }

    fun discard(transport: UsbTransport) {
        try { transport.close() } catch (_: Exception) {}
        if (alive.decrementAndGet() == 0) onAllChannelsDead()
    }

    fun closeAll() {
        while (true) {
            val t = queue.poll() ?: break
            try { t.close() } catch (_: Exception) {}
        }
    }
}
