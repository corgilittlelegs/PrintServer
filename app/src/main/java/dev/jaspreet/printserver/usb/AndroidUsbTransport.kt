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
            if (n < 0) throw IOException("USB bulk write failed at offset $off")
            off += n
            left -= n
        }
    }

    override fun read(buffer: ByteArray): Int {
        val n = connection.bulkTransfer(inEndpoint, buffer, buffer.size, READ_TIMEOUT_MS)
        if (n < 0) throw IOException("USB bulk read failed or timed out")
        return n
    }

    override fun close() {
        try { connection.releaseInterface(iface) } catch (_: Exception) {}
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000   // responses can lag while the printer chews a job
    }
}
