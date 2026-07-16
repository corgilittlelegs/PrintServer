package dev.jaspreet.printserver.usb

import java.io.InputStream
import java.io.OutputStream

class UsbTransportInputStream(private val transport: UsbTransport) : InputStream() {
    private val buf = ByteArray(16384)
    private var pos = 0
    private var end = 0

    private fun fill(): Boolean {
        if (pos < end) return true
        end = transport.read(buf)
        pos = 0
        return end > 0
    }

    override fun read(): Int {
        if (!fill()) return -1
        return buf[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, end - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }
}

class UsbTransportOutputStream(private val transport: UsbTransport) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) = transport.write(b, off, len)
}
