package dev.jaspreet.printserver.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdTest {

    @Test
    fun `parses a well-formed HP device id string`() {
        val raw = "MFG:HP;CMD:PCL,PJL,POSTSCRIPT;MDL:DeskJet 2700 series;CLS:PRINTER;DES:HP DeskJet 2700 series;"
        val info = DeviceId.parse(raw)
        assertEquals("HP", info.manufacturer)
        assertEquals("DeskJet 2700 series", info.model)
        assertEquals(listOf("PCL", "PJL", "POSTSCRIPT"), info.commands)
    }

    @Test
    fun `accepts MANUFACTURER and MODEL long-form keys`() {
        val raw = "MANUFACTURER:Canon;MODEL:PIXMA MG3600;COMMAND SET:BJL,BJRaster3;"
        val info = DeviceId.parse(raw)
        assertEquals("Canon", info.manufacturer)
        assertEquals("PIXMA MG3600", info.model)
        assertEquals(listOf("BJL", "BJRaster3"), info.commands)
    }

    @Test
    fun `returns empty info for null input`() {
        val info = DeviceId.parse(null)
        assertEquals(null, info.manufacturer)
        assertEquals(null, info.model)
        assertEquals(emptyList<String>(), info.commands)
    }

    @Test
    fun `returns empty info for blank input`() {
        val info = DeviceId.parse("   ")
        assertEquals(null, info.manufacturer)
        assertEquals(null, info.model)
        assertEquals(emptyList<String>(), info.commands)
    }

    @Test
    fun `ignores malformed segments without a colon`() {
        val raw = "MFG:HP;garbage-no-colon;MDL:OfficeJet;"
        val info = DeviceId.parse(raw)
        assertEquals("HP", info.manufacturer)
        assertEquals("OfficeJet", info.model)
    }

    @Test
    fun `missing fields stay null or empty rather than throwing`() {
        val raw = "CLS:PRINTER;DES:Some Printer;"
        val info = DeviceId.parse(raw)
        assertEquals(null, info.manufacturer)
        assertEquals(null, info.model)
        assertEquals(emptyList<String>(), info.commands)
    }
}
