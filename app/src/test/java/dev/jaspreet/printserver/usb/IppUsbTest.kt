package dev.jaspreet.printserver.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IppUsbTest {
    @Test
    fun `detects ipp-usb interface descriptor 7-1-4`() {
        assertTrue(IppUsb.isIppUsb(7, 1, 4))
        assertFalse(IppUsb.isIppUsb(7, 1, 2))   // classic bidirectional printer
        assertFalse(IppUsb.isIppUsb(8, 1, 4))   // mass storage
    }

    @Test
    fun `detects legacy printer interface`() {
        assertTrue(IppUsb.isLegacyPrinter(7, 1, 1))
        assertTrue(IppUsb.isLegacyPrinter(7, 1, 2))
        assertFalse(IppUsb.isLegacyPrinter(7, 1, 4))
        assertFalse(IppUsb.isLegacyPrinter(3, 0, 0))
    }
}
