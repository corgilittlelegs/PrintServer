package dev.jaspreet.printserver.usb

/**
 * One printer byte channel: for IPP-USB, a claimed USB interface pair
 * (bulk OUT + bulk IN). All methods are blocking.
 */
interface UsbTransport {
    /** Writes exactly [length] bytes from [data] starting at [offset]. Throws IOException on failure. */
    fun write(data: ByteArray, offset: Int, length: Int)

    /** Reads at least 1 byte into [buffer], returns count. Throws IOException on failure or timeout. */
    fun read(buffer: ByteArray): Int

    fun close()
}
