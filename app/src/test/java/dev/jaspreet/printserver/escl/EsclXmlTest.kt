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
        assertTrue(xml.contains("<scan:BrightnessSupport>"))
        assertTrue(xml.contains("<scan:ContrastSupport>"))
        assertTrue(xml.contains("<scan:Normal>1000</scan:Normal>"))
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
              <scan:Brightness>1200</scan:Brightness>
              <scan:Contrast>800</scan:Contrast>
              <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
            </scan:ScanSettings>
        """.trimIndent()
        val settings = EsclXml.parseScanSettings(request)
        assertEquals(600, settings.resolution)
        assertEquals(ScanColorMode.GRAYSCALE, settings.colorMode)
        assertEquals(1200, settings.brightness)
        assertEquals(800, settings.contrast)
    }

    @Test
    fun `parses unprefixed brightness and contrast from Windows-style ScanSettings`() {
        val request = """
            <ScanSettings xmlns="http://schemas.hp.com/imaging/escl/2011/05/03">
              <XResolution>300</XResolution>
              <Brightness>3</Brightness>
              <Contrast>-12</Contrast>
              <ColorMode>RGB24</ColorMode>
            </ScanSettings>
        """.trimIndent()
        val settings = EsclXml.parseScanSettings(request)
        assertEquals(300, settings.resolution)
        assertEquals(ScanColorMode.COLOR, settings.colorMode)
        assertEquals(3, settings.brightness)
        assertEquals(-12, settings.contrast)
    }

    @Test
    fun `parseScanSettings defaults optional fields to null when absent`() {
        val request = "<scan:ScanSettings><pwg:InputSource>Platen</pwg:InputSource></scan:ScanSettings>"
        val settings = EsclXml.parseScanSettings(request)
        assertNull(settings.resolution)
        assertNull(settings.colorMode)
        assertNull(settings.brightness)
        assertNull(settings.contrast)
    }

    @Test
    fun `builds a ScannerStatus response reporting Idle with no jobs`() {
        val xml = EsclXml.scannerStatus(jobs = emptyList())
        assertTrue(xml.contains("<pwg:State>Idle</pwg:State>"))
    }

    @Test
    fun `builds a ScannerStatus response reporting Processing with an active job`() {
        val xml = EsclXml.scannerStatus(jobs = listOf(EsclJobInfo(id = "1", state = "Processing")))
        assertTrue(xml.contains("<pwg:State>Processing</pwg:State>"))
        assertTrue(xml.contains("<pwg:JobUri>/eSCL/ScanJobs/1</pwg:JobUri>"))
        assertTrue(xml.contains("<pwg:JobState>Processing</pwg:JobState>"))
        assertTrue(xml.contains("<pwg:ImagesCompleted>0</pwg:ImagesCompleted>"))
        assertTrue(xml.contains("<pwg:ImagesToTransfer>0</pwg:ImagesToTransfer>"))
        assertTrue(xml.contains("<pwg:JobStateReason>None</pwg:JobStateReason>"))
    }

    @Test
    fun `builds a ScannerStatus response reporting a completed job with image counts`() {
        val xml = EsclXml.scannerStatus(
            jobs = listOf(
                EsclJobInfo(
                    id = "2",
                    state = "Completed",
                    ageSeconds = 7,
                    imagesCompleted = 1,
                    imagesToTransfer = 1,
                    stateReason = "JobCompletedSuccessfully",
                ),
            ),
        )
        assertTrue(xml.contains("<pwg:State>Idle</pwg:State>"))
        assertTrue(xml.contains("<pwg:JobUri>/eSCL/ScanJobs/2</pwg:JobUri>"))
        assertTrue(xml.contains("<pwg:JobUuid>urn:uuid:2</pwg:JobUuid>"))
        assertTrue(xml.contains("<scan:Age>7</scan:Age>"))
        assertTrue(xml.contains("<pwg:ImagesCompleted>1</pwg:ImagesCompleted>"))
        assertTrue(xml.contains("<pwg:ImagesToTransfer>1</pwg:ImagesToTransfer>"))
        assertTrue(xml.contains("<pwg:JobState>Completed</pwg:JobState>"))
        assertTrue(xml.contains("<pwg:JobStateReason>JobCompletedSuccessfully</pwg:JobStateReason>"))
    }
}
