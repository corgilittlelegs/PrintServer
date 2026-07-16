package dev.jaspreet.printserver.ipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TxtRecordsTest {

    @Test
    fun `builds ipp everywhere txt records`() {
        val info = PrinterInfo(
            makeAndModel = "Test Laser 9000",
            formats = listOf("application/pdf", "image/pwg-raster"),
            color = true,
            uuid = "11111111-2222-3333-4444-555555555555",
            urf = listOf("V1.4", "W8", "SRGB24"),
        )
        val txt = TxtRecords.forIpp(info)
        assertEquals("1", txt["txtvers"])
        assertEquals("ipp/print", txt["rp"])
        assertEquals("application/pdf,image/pwg-raster", txt["pdl"])
        assertEquals("T", txt["color"])
        assertEquals("11111111-2222-3333-4444-555555555555", txt["UUID"])
        assertEquals("V1.4,W8,SRGB24", txt["URF"])
        assertEquals("Test Laser 9000", txt["ty"])
        assertEquals("1", txt["qtotal"])
    }

    @Test
    fun `omits URF when printer reports none`() {
        val info = PrinterInfo("X", listOf("application/pdf"), false, null, emptyList())
        val txt = TxtRecords.forIpp(info)
        assertFalse(txt.containsKey("URF"))
        assertFalse(txt.containsKey("UUID"))
        assertEquals("F", txt["color"])
    }
}
