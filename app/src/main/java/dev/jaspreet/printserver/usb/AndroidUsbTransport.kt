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
     *  bulkTransfer result as "nothing yet, not an error" and loops retrying against a
     *  shrinking timeout budget rather than failing immediately. That was tried here
     *  too, but hardware testing against a real DeskJet 2300-series unit's LEDM scan
     *  interface showed it made things measurably *worse* -- a tight retry loop with no
     *  delay between iterations appears to further destabilize an already-flaky
     *  interface rather than ride out a transient blip, turning intermittent failures
     *  into consistent ones. Reverted to a single-shot read; a zero-length result is
     *  surfaced immediately and handled by the caller's own retry logic
     *  (`ServerService.scanWithRetry`), which spaces attempts seconds apart instead of
     *  hammering the device. Revisit only with real USB traffic-capture evidence, not
     *  further guessing. */
    override fun read(buffer: ByteArray): Int {
        val n = connection.bulkTransfer(inEndpoint, buffer, buffer.size, READ_TIMEOUT_MS)
        if (n < 0) throw IOException("USB bulk read failed or timed out")
        return n
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
