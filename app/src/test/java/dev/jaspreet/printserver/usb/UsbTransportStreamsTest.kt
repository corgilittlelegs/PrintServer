package dev.jaspreet.printserver.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbTransportStreamsTest {

    @Test
    fun `input stream reads across transport packet boundaries`() {
        val fake = FakePrinterTransport { "HELLOWORLD".toByteArray() }
        fake.write("x".toByteArray(), 0, 1) // trigger a pending response
        val input = UsbTransportInputStream(fake)
        val out = ByteArray(10)
        var read = 0
        while (read < 10) {
            val n = input.read(out, read, 10 - read)
            if (n < 0) break
            read += n
        }
        assertEquals("HELLOWORLD", String(out, 0, read))
    }

    @Test
    fun `output stream forwards writes to transport`() {
        val fake = FakePrinterTransport { ByteArray(0) }
        val output = UsbTransportOutputStream(fake)
        output.write("abc".toByteArray())
        output.flush()
        assertEquals("abc", String(fake.lastRequest()))
    }
}
