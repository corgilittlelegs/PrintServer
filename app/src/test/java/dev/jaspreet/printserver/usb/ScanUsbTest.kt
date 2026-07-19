package dev.jaspreet.printserver.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanUsbTest {
    @Test
    fun `detects LEDM scan interface descriptor 255-4`() {
        assertTrue(ScanUsb.isLedmScan(255, 4))
        assertFalse(ScanUsb.isLedmScan(255, 204))  // HP's status/control interface, not scan data
        assertFalse(ScanUsb.isLedmScan(7, 1))       // print interface
    }
}
