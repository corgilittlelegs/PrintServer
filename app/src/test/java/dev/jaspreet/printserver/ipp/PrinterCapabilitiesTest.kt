package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Types
import dev.jaspreet.printserver.jobs.PrintQuality
import dev.jaspreet.printserver.profile.VerifiedPrinterProfiles
import dev.jaspreet.printserver.render.NativeRenderingPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class PrinterCapabilitiesTest {

    private val caps = PrinterCapabilities.fromProfile(
        VerifiedPrinterProfiles.DESKJET_2300,
        URI.create("ipp://192.168.1.5:8631/ipp/print"),
    )

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

    @Test
    fun `advertises draft normal and high print quality`() {
        val group = caps.asPrinterAttributes()
        assertEquals(
            listOf(
                com.hp.jipp.model.PrintQuality.draft,
                com.hp.jipp.model.PrintQuality.normal,
                com.hp.jipp.model.PrintQuality.high,
            ),
            group.getValues(Types.printQualitySupported),
        )
        assertEquals(com.hp.jipp.model.PrintQuality.normal, group.getValue(Types.printQualityDefault))
    }

    // --- Task 4: resolution/quality advertisement must match what NativeRenderingPipeline
    // actually produces, not aspirational printer-spec numbers. ---

    @Test
    fun `advertises exactly the resolutions the tier2 renderer can produce for every reachable quality`() {
        val group = caps.asPrinterAttributes()
        val advertisedDpis = group.getValues(Types.printerResolutionSupported).map { it.x }.toSet()

        // Ground truth: NativeRenderingPipeline.dpiFor(quality) for every PrintQuality reachable
        // from IPP (DRAFT/NORMAL/HIGH — Photo/1200dpi is intentionally unreachable per
        // PrintOptions.kt, so it has no enum value and can't leak into this set). Calling the
        // real (internal, non-private) function directly rather than hand-copying the mapping
        // or reflecting into a private method means this test fails loudly on a real signature
        // change instead of drifting silently or breaking with an opaque reflection exception.
        val reachableDpis = PrintQuality.entries.map { quality -> NativeRenderingPipeline.dpiFor(quality) }.toSet()

        assertEquals(reachableDpis, advertisedDpis)
        assertFalse("must never advertise the unreachable 1200dpi Photo mode", advertisedDpis.contains(1200))
    }

    @Test
    fun `default resolution is 600dpi matching normal quality default`() {
        val group = caps.asPrinterAttributes()
        val default = group.getValue(Types.printerResolutionDefault)!!
        assertEquals(600, default.x)
        assertEquals(600, default.y)
    }

    @Test
    fun `URF RS token lists every supported resolution in one PWG5100_13 token`() {
        val info = caps.toPrinterInfo()
        assertTrue("expected RS300-600 in URF tokens, got ${info.urf}", info.urf.contains("RS300-600"))
        assertFalse(info.urf.any { it.startsWith("RS") && it.contains("1200") })
    }

    @Test
    fun `URF PQ token lists every supported print-quality code in one token`() {
        val info = caps.toPrinterInfo()
        assertTrue("expected PQ3-4-5 in URF tokens, got ${info.urf}", info.urf.contains("PQ3-4-5"))
    }
}
