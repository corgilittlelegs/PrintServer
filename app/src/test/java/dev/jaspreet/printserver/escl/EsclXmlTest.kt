package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScannerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EsclXmlTest {

    @Test
    fun `builds a ScannerCapabilities response listing resolutions and color modes`() {
        val caps = ScannerCapabilities(
            maxWidth = 2550, maxHeight = 3300,
            supportedResolutions = listOf(75, 150, 300, 600),
            supportedColorModes = setOf(ScanColorMode.COLOR, ScanColorMode.GRAYSCALE),
        )
        val xml = EsclXml.scannerCapabilities(caps, makeAndModel = "PrintServer Scanner")

        assertTrue(xml.contains("<pwg:MakeAndModel>PrintServer Scanner</pwg:MakeAndModel>"))
        assertTrue(xml.contains("<scan:MaxWidth>2550</scan:MaxWidth>"))
        assertTrue(xml.contains("<scan:MaxHeight>3300</scan:MaxHeight>"))
        assertTrue(xml.contains("<scan:ColorMode>RGB24</scan:ColorMode>"))
        assertTrue(xml.contains("<scan:ColorMode>Grayscale8</scan:ColorMode>"))
        assertTrue(xml.contains("<scan:XResolution>300</scan:XResolution>"))
        assertTrue(xml.contains("<scan:XResolution>600</scan:XResolution>"))
        assertTrue(xml.contains("image/jpeg"))
    }

    @Test
    fun `a grayscale-only device's capabilities omit RGB24`() {
        val caps = ScannerCapabilities(
            maxWidth = 2550, maxHeight = 3300,
            supportedResolutions = listOf(300),
            supportedColorModes = setOf(ScanColorMode.GRAYSCALE),
        )
        val xml = EsclXml.scannerCapabilities(caps, makeAndModel = "PrintServer Scanner")
        assertFalse(xml.contains("RGB24"))
        assertTrue(xml.contains("Grayscale8"))
    }

    @Test
    fun `parses resolution and color mode from a ScanSettings request`() {
        val request = """
            <scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
              <pwg:Version>2.63</pwg:Version>
              <pwg:InputSource>Platen</pwg:InputSource>
              <scan:ColorMode>Grayscale8</scan:ColorMode>
              <scan:XResolution>600</scan:XResolution>
              <scan:YResolution>600</scan:YResolution>
              <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
            </scan:ScanSettings>
        """.trimIndent()
        val settings = EsclXml.parseScanSettings(request)
        assertEquals(600, settings.resolution)
        assertEquals(ScanColorMode.GRAYSCALE, settings.colorMode)
    }

    @Test
    fun `parseScanSettings defaults resolution and color mode to null when absent`() {
        val request = "<scan:ScanSettings><pwg:InputSource>Platen</pwg:InputSource></scan:ScanSettings>"
        val settings = EsclXml.parseScanSettings(request)
        assertNull(settings.resolution)
        assertNull(settings.colorMode)
    }

    @Test
    fun `builds a ScannerStatus response reporting Idle with no jobs`() {
        val xml = EsclXml.scannerStatus(jobs = emptyList())
        assertTrue(xml.contains("<scan:State>Idle</scan:State>"))
    }

    @Test
    fun `builds a ScannerStatus response reporting Processing with an active job`() {
        val xml = EsclXml.scannerStatus(jobs = listOf(EsclJobInfo(id = "1", state = "Processing")))
        assertTrue(xml.contains("<scan:State>Processing</scan:State>"))
        assertTrue(xml.contains("<pwg:JobUri>/eSCL/ScanJobs/1</pwg:JobUri>"))
        assertTrue(xml.contains("<pwg:JobState>Processing</pwg:JobState>"))
    }
}
