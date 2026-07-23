package dev.jaspreet.printserver.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanUsbTest {
    @Test
    fun `detects HPLIP LEDM scan channel descriptor 255-204-0`() {
        assertTrue(ScanUsb.isLedmScan(255, 204, 0))
        assertFalse(ScanUsb.isLedmScan(255, 4, 1))
        assertFalse(ScanUsb.isLedmScan(7, 1, 2))
    }
}
