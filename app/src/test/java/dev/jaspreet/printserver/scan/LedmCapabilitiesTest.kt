package dev.jaspreet.printserver.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class LedmCapabilitiesTest {

    // Shape confirmed against a real HP DeskJet 2300-series unit's GET /Scan/ScanCaps
    // response: each <Resolution> wraps an <XResolution>/<YResolution> pair rather than
    // being a bare digit itself, and the size/resolution fields sit inside a nested
    // <InputSourceCaps> under <Platen>.
    private val sampleScanCaps = """
        <ScanCaps>
          <ColorEntries>
            <ColorEntry><ColorType>Color8</ColorType></ColorEntry>
            <ColorEntry><ColorType>Gray8</ColorType></ColorEntry>
          </ColorEntries>
          <Platen>
            <InputSourceCaps>
              <MinWidth>50</MinWidth>
              <MinHeight>50</MinHeight>
              <MaxWidth>2550</MaxWidth>
              <MaxHeight>3300</MaxHeight>
              <MaxOpticalXResolution>1200</MaxOpticalXResolution>
              <MaxOpticalYResolution>1200</MaxOpticalYResolution>
              <SupportedResolutions>
                <Resolution><XResolution>75</XResolution><YResolution>75</YResolution></Resolution>
                <Resolution><XResolution>150</XResolution><YResolution>150</YResolution></Resolution>
                <Resolution><XResolution>300</XResolution><YResolution>300</YResolution></Resolution>
                <Resolution><XResolution>600</XResolution><YResolution>600</YResolution></Resolution>
              </SupportedResolutions>
            </InputSourceCaps>
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
                <InputSourceCaps>
                  <MinWidth>50</MinWidth><MinHeight>50</MinHeight>
                  <MaxWidth>2550</MaxWidth><MaxHeight>3300</MaxHeight>
                  <SupportedResolutions>
                    <Resolution><XResolution>300</XResolution><YResolution>300</YResolution></Resolution>
                  </SupportedResolutions>
                </InputSourceCaps>
              </Platen>
            </ScanCaps>
        """.trimIndent()
        val caps = LedmCapabilities.parse(grayOnly)
        assertEquals(setOf(ScanColorMode.GRAYSCALE), caps.supportedColorModes)
    }
}
