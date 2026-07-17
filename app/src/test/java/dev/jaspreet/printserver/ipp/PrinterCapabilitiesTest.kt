package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class PrinterCapabilitiesTest {

    private val caps = PrinterCapabilities.deskJet2300(URI.create("ipp://192.168.1.5:8631/ipp/print"))

    @Test
    fun `advertises pdf as the default with jpeg also supported`() {
        val group = caps.asPrinterAttributes()
        assertEquals(listOf("application/pdf", "image/jpeg"), group.getValues(Types.documentFormatSupported))
        assertEquals("application/pdf", group.getValue(Types.documentFormatDefault))
    }

    @Test
    fun `reports required identity and state attributes`() {
        val group = caps.asPrinterAttributes()
        assertTrue(group.getValue(Types.printerMakeAndModel)!!.value.contains("DeskJet 2300"))
        assertEquals(true, group.getValue(Types.colorSupported))
        assertTrue(group.getValues(Types.ippVersionsSupported).contains("2.0"))
        assertTrue(group.getValues(Types.operationsSupported).isNotEmpty())
    }

    @Test
    fun `printer info feeds txt records`() {
        val info = caps.toPrinterInfo()
        assertEquals(listOf("application/pdf", "image/jpeg"), info.formats)
        assertTrue(info.color)
        val txt = TxtRecords.forIpp(info)
        assertEquals("application/pdf,image/jpeg", txt["pdl"])
    }
}
