package dev.jaspreet.printserver.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException

class AndroidUsbTransport(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val zeroReadRetries: Int = 0,
    private val zeroReadDelayMs: Long = 0,
) : UsbTransport {

    override fun write(data: ByteArray, offset: Int, length: Int) {
        var off = offset
        var left = length
        while (left > 0) {
            val chunk = minOf(left, 16384)
            val n = connection.bulkTransfer(outEndpoint, data, off, chunk, WRITE_TIMEOUT_MS)
            if (n <= 0) throw IOException("USB bulk write failed at offset $off")
            off += n
            left -= n
        }
    }

    /** NOTE: HPLIP's own USB layer (io/hpmud/musb.c's musb_read) treats a 0-byte
     *  bulkTransfer result as "nothing yet, not an error". A prior tight retry loop with
     *  no delay made the real DeskJet 2300-series scan interface less reliable, so the
     *  default remains single-shot. The scan interface can opt into a small delayed
     *  recovery budget, which gives the motor-start transient time to settle without
     *  hammering the USB pipe. */
    override fun read(buffer: ByteArray): Int {
        var zeroReads = 0
        while (true) {
            val n = connection.bulkTransfer(inEndpoint, buffer, buffer.size, READ_TIMEOUT_MS)
            if (n < 0) throw IOException("USB bulk read failed or timed out")
            if (n > 0) return n
            if (zeroReads >= zeroReadRetries) return 0
            zeroReads++
            if (zeroReadDelayMs > 0) Thread.sleep(zeroReadDelayMs)
        }
    }

    override fun close() {
        try { connection.releaseInterface(iface) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 30_000
        // HPLIP's own LEDM transport (io/hpmud/musb.c) uses a 10s read timeout, even for
        // the same status/poll/fetch requests this class issues -- confirmed against
        // hardware that a much longer timeout (this was previously 60s) just means a
        // dead read spins for a full minute before the caller's own retry logic ever
        // gets a chance to try again, instead of failing fast and letting ScanPipeline's
        // poll loop (which already waits between iterations, up to maxPolls times) or
        // ServerService's scanWithRetry handle the actual "give it more time" job.
        const val READ_TIMEOUT_MS = 10_000
    }
}
