package dev.jaspreet.printserver.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class LedmCapabilitiesTest {

    private val sampleScanCaps = """
        <ScanCaps>
          <ColorEntries>
            <ColorEntry><ColorType>Color8</ColorType></ColorEntry>
            <ColorEntry><ColorType>Gray8</ColorType></ColorEntry>
          </ColorEntries>
          <Platen>
            <MinWidth>50</MinWidth>
            <MinHeight>50</MinHeight>
            <MaxWidth>2550</MaxWidth>
            <MaxHeight>3300</MaxHeight>
            <OpticalResolutionWidth>1200</OpticalResolutionWidth>
            <OpticalResolutionHeight>1200</OpticalResolutionHeight>
            <SupportedResolutions>
              <Resolution>75</Resolution>
              <Resolution>150</Resolution>
              <Resolution>300</Resolution>
              <Resolution>600</Resolution>
            </SupportedResolutions>
          </Platen>
        </ScanCaps>
    """.trimIndent()

    @Test
    fun `parses the platen max size`() {
        val caps = LedmCapabilities.parse(sampleScanCaps)
        assertEquals(2550, caps.maxWidth)
        assertEquals(3300, caps.maxHeight)
    }

    @Test
    fun `parses the supported resolutions in document order`() {
        val caps = LedmCapabilities.parse(sampleScanCaps)
        assertEquals(listOf(75, 150, 300, 600), caps.supportedResolutions)
    }

    @Test
    fun `parses the supported color modes, mapping Color8 and Gray8 to ScanColorMode`() {
        val caps = LedmCapabilities.parse(sampleScanCaps)
        assertEquals(setOf(ScanColorMode.COLOR, ScanColorMode.GRAYSCALE), caps.supportedColorModes)
    }

    @Test
    fun `a device reporting only Gray8 has no COLOR mode`() {
        val grayOnly = """
            <ScanCaps>
              <ColorEntries><ColorEntry><ColorType>Gray8</ColorType></ColorEntry></ColorEntries>
              <Platen>
                <MinWidth>50</MinWidth><MinHeight>50</MinHeight>
                <MaxWidth>2550</MaxWidth><MaxHeight>3300</MaxHeight>
                <SupportedResolutions><Resolution>300</Resolution></SupportedResolutions>
              </Platen>
            </ScanCaps>
        """.trimIndent()
        val caps = LedmCapabilities.parse(grayOnly)
        assertEquals(setOf(ScanColorMode.GRAYSCALE), caps.supportedColorModes)
    }
}
