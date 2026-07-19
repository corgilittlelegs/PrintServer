package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScannerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class EsclTxtRecordsTest {
    @Test
    fun `builds the standard eSCL TXT record keys`() {
        val caps = ScannerCapabilities(
            maxWidth = 2550, maxHeight = 3300,
            supportedResolutions = listOf(300, 600),
            supportedColorModes = setOf(ScanColorMode.COLOR, ScanColorMode.GRAYSCALE),
        )
        val txt = EsclTxtRecords.forEscl(caps, makeAndModel = "PrintServer Scanner")
        assertEquals("PrintServer Scanner", txt["ty"])
        assertEquals("image/jpeg", txt["pdl"])
        assertEquals("t", txt["rs"])
        assertEquals("2.63", txt["vers"])
    }
}
