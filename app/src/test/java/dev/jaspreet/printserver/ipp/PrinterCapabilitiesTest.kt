package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class PrinterCapabilitiesTest {

    private val caps = PrinterCapabilities.deskJet2300(URI.create("ipp://192.168.1.5:8631/ipp/print"))

    @Test
    fun `advertises pdf as the default with pwg raster and jpeg also supported`() {
        val group = caps.asPrinterAttributes()
        assertEquals(
            listOf("application/pdf", "image/pwg-raster", "image/jpeg"),
            group.getValues(Types.documentFormatSupported),
        )
        assertEquals("application/pdf", group.getValue(Types.documentFormatDefault))
    }

    @Test
    fun `reports required identity and state attributes`() {
        val group = caps.asPrinterAttributes()
        assertEquals("PrintServer Bridge", group.getValue(Types.printerMakeAndModel)!!.value)
        assertEquals(true, group.getValue(Types.colorSupported))
        assertTrue(group.getValues(Types.ippVersionsSupported).contains("2.0"))
        assertTrue(group.getValues(Types.operationsSupported).isNotEmpty())
    }

    @Test
    fun `printer info feeds txt records`() {
        val info = caps.toPrinterInfo()
        assertEquals(listOf("application/pdf", "image/pwg-raster", "image/jpeg"), info.formats)
        assertTrue(info.color)
        val txt = TxtRecords.forIpp(info)
        assertEquals("application/pdf,image/pwg-raster,image/jpeg", txt["pdl"])
    }

    @Test
    fun `printer info carries a non-empty URF token so macOS mDNS browse classifies it as AirPrint-capable`() {
        val info = caps.toPrinterInfo()
        assertTrue("expected non-empty URF tokens, got ${info.urf}", info.urf.isNotEmpty())
        val txt = TxtRecords.forIpp(info)
        assertTrue("TXT record should carry URF key", txt.containsKey("URF"))
    }

    @Test
    fun `reports the RFC 2911 mandatory printer attributes that IPP-Everywhere clients validate before trusting a driverless printer`() {
        val group = caps.asPrinterAttributes()
        assertEquals(listOf("none"), group.getValues(Types.uriAuthenticationSupported))
        assertEquals(listOf("none"), group.getValues(Types.uriSecuritySupported))
        assertEquals(false, group.getValue(Types.multipleDocumentJobsSupported))
        assertTrue("printer-up-time must be a positive integer per RFC 2911", (group.getValue(Types.printerUpTime) ?: 0) >= 0)
    }

    @Test
    fun `advertised name is not a real vendor model string that could steer macOS toward a bundled driver`() {
        assertFalse(
            "makeAndModel should not be a genuine HP retail model name: ${caps.makeAndModel}",
            caps.makeAndModel.contains("HP"),
        )
    }
}
