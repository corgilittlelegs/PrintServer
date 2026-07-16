package dev.jaspreet.printserver.usb

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Scripted printer: buffers writes; on the first read after a write burst,
 * calls [respond] with everything written so far and serves the reply bytes.
 */
class FakePrinterTransport(private val respond: (ByteArray) -> ByteArray) : UsbTransport {
    private val reqBuf = ByteArrayOutputStream()
    private var lastReq: ByteArray = ByteArray(0)
    private var pending: ByteArray = ByteArray(0)
    private var pos = 0
    var closed = false
        private set

    fun lastRequest(): ByteArray = lastReq

    @Synchronized
    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (closed) throw IOException("closed")
        reqBuf.write(data, offset, length)
        lastReq = reqBuf.toByteArray()
    }

    @Synchronized
    override fun read(buffer: ByteArray): Int {
        if (closed) throw IOException("closed")
        if (pos >= pending.size) {
            if (reqBuf.size() == 0) throw IOException("fake printer: nothing to respond to")
            pending = respond(reqBuf.toByteArray())
            reqBuf.reset()
            pos = 0
        }
        val n = minOf(buffer.size, pending.size - pos)
        System.arraycopy(pending, pos, buffer, 0, n)
        pos += n
        return n
    }

    @Synchronized
    override fun close() { closed = true }
}
