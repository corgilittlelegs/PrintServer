# eSCL Scan Server (Spec B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Any device on the LAN discovers this app as a network scanner via mDNS `_uscan._tcp` and scans directly from its native scan app — zero drivers — the same driverless philosophy `LocalIppServer` already delivers for printing.

**Architecture:** `LocalEsclServer` (new `escl` package) is a direct sibling of `LocalIppServer`: its own `ServerSocket`, its own accept-loop, reusing the existing `http/` package for HTTP parsing. It translates eSCL's XML wire format (`EsclXml`) to/from Spec A's `ScanPipeline` (widened here with resolution/color-mode parameters), backed by a live capabilities query (`LedmCapabilities`, mirroring Tier 1's `PrinterQuery` pattern) rather than any hardcoded/guessed resolution list.

**Tech Stack:** Kotlin, existing `UsbTransport`/`UsbPrinterManager` USB abstraction, existing `http/` package (`HttpHead`, `BodyReader`), existing `DiscoveryAdvertiser`/`NsdAdvertiser`, JUnit.

Spec: `docs/superpowers/specs/2026-07-19-escl-scan-server-design.md`

**Note on eSCL XML fidelity:** Unlike Spec A's LEDM wire format (read directly from HPLIP's source, byte-for-byte confirmed), the eSCL XML shapes in this plan follow the well-known public Mopria/AirScan eSCL schema conventions, not a freshly-read authoritative spec document. Task 6's hardware test against a real client (macOS Image Capture or equivalent) is the actual fidelity check — if a real client rejects a response, fix the XML to match what the client logs/expects rather than guessing further, and prefer cross-checking against a known-good reference implementation (e.g. OpenPrinting's `sane-airscan` source) over re-guessing.

---

### Task 1: `ScanColorMode` + widen `LedmRequests`/`ScanPipeline` for resolution and color mode

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/scan/ScanColorMode.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/scan/LedmRequests.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/scan/ScanPipeline.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/scan/LedmRequestsTest.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/scan/ScanPipelineTest.kt`

- [ ] **Step 1: Create `ScanColorMode.kt`**

```kotlin
package dev.jaspreet.printserver.scan

/** Maps to LEDM's ColorSpace field: COLOR->Color (Color8), GRAYSCALE->Gray (Gray8).
 *  LEDM's ce_element table also defines a K1 (1-bit black-and-white) mode, but
 *  bb_ledm.c's own job-creation code hardcodes BitDepth=8 on every branch of its
 *  ternary regardless of mode -- true 1-bit output isn't reachable through this
 *  protocol path, so it's intentionally not modeled here. */
enum class ScanColorMode { COLOR, GRAYSCALE }
```

- [ ] **Step 2: Write the failing test for `LedmRequests.createJobBody`'s new parameter**

Add to `LedmRequestsTest.kt` (the existing `createJobBody` test currently doesn't pass a
`colorSpace` argument — update that existing test's call site too, adding the new
parameter, rather than leaving it on an old signature):

```kotlin
    @Test
    fun `builds the create-job XML body with the given scan settings`() {
        val body = LedmRequests.createJobBody(
            xResolution = 300, yResolution = 300,
            xStart = 0, width = 2550, yStart = 0, height = 3300,
            colorSpace = "Color",
        )
        assertTrue(body.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(body.contains("<XResolution>300</XResolution>"))
        assertTrue(body.contains("<YResolution>300</YResolution>"))
        assertTrue(body.contains("<XStart>0</XStart>"))
        assertTrue(body.contains("<Width>2550</Width>"))
        assertTrue(body.contains("<YStart>0</YStart>"))
        assertTrue(body.contains("<Height>3300</Height>"))
        assertTrue(body.contains("<Format>Jpeg</Format>"))
        assertTrue(body.contains("<ColorSpace>Color</ColorSpace>"))
        assertTrue(body.contains("<InputSource>Platen</InputSource>"))
        assertTrue(body.endsWith("</ScanSettings>"))
    }

    @Test
    fun `builds the create-job XML body with grayscale color space`() {
        val body = LedmRequests.createJobBody(
            xResolution = 300, yResolution = 300,
            xStart = 0, width = 2550, yStart = 0, height = 3300,
            colorSpace = "Gray",
        )
        assertTrue(body.contains("<ColorSpace>Gray</ColorSpace>"))
    }
```

- [ ] **Step 3: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.LedmRequestsTest"`
Expected: FAIL — `createJobBody` doesn't accept a `colorSpace` parameter yet, and the
existing test's call site is now missing a required argument (compile error).

- [ ] **Step 4: Widen `createJobBody`**

In `LedmRequests.kt`, replace:

```kotlin
    fun createJobBody(
        xResolution: Int,
        yResolution: Int,
        xStart: Int,
        width: Int,
        yStart: Int,
        height: Int,
    ): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<ScanSettings xmlns=\"http://www.hp.com/schemas/imaging/con/cnx/scan/2008/08/19\">" +
            "<XResolution>$xResolution</XResolution>" +
            "<YResolution>$yResolution</YResolution>" +
            "<XStart>$xStart</XStart>" +
            "<Width>$width</Width>" +
            "<YStart>$yStart</YStart>" +
            "<Height>$height</Height>" +
            "<Format>Jpeg</Format>" +
            "<CompressionQFactor>15</CompressionQFactor>" +
            "<ColorSpace>Color</ColorSpace>" +
            "<BitDepth>8</BitDepth>" +
            "<InputSource>Platen</InputSource>" +
            "<InputSourceType>Platen</InputSourceType>" +
            "<GrayRendering>NTSC</GrayRendering>" +
            "<ToneMap><Gamma>0</Gamma><Brightness>0</Brightness><Contrast>0</Contrast>" +
            "<Highlite>0</Highlite><Shadow>0</Shadow></ToneMap>" +
            "<ContentType>Photo</ContentType></ScanSettings>"
```

with:

```kotlin
    fun createJobBody(
        xResolution: Int,
        yResolution: Int,
        xStart: Int,
        width: Int,
        yStart: Int,
        height: Int,
        colorSpace: String,
    ): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<ScanSettings xmlns=\"http://www.hp.com/schemas/imaging/con/cnx/scan/2008/08/19\">" +
            "<XResolution>$xResolution</XResolution>" +
            "<YResolution>$yResolution</YResolution>" +
            "<XStart>$xStart</XStart>" +
            "<Width>$width</Width>" +
            "<YStart>$yStart</YStart>" +
            "<Height>$height</Height>" +
            "<Format>Jpeg</Format>" +
            "<CompressionQFactor>15</CompressionQFactor>" +
            "<ColorSpace>$colorSpace</ColorSpace>" +
            "<BitDepth>8</BitDepth>" +
            "<InputSource>Platen</InputSource>" +
            "<InputSourceType>Platen</InputSourceType>" +
            "<GrayRendering>NTSC</GrayRendering>" +
            "<ToneMap><Gamma>0</Gamma><Brightness>0</Brightness><Contrast>0</Contrast>" +
            "<Highlite>0</Highlite><Shadow>0</Shadow></ToneMap>" +
            "<ContentType>Photo</ContentType></ScanSettings>"
```

- [ ] **Step 5: Run the `LedmRequests` tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.LedmRequestsTest"`
Expected: PASS (all tests, including the two above)

- [ ] **Step 6: Write the failing test for `ScanPipeline`'s new parameters**

Add to `ScanPipelineTest.kt` (reuses the existing `chunkedResponse`/`binaryChunkedResponse`
helpers already in that file):

```kotlin
    @Test
    fun `passes the requested resolution and grayscale color space into the create-job request`() {
        var capturedJobBody = ""
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
        val transport = FakePrinterTransport { reqBytes ->
            val req = String(reqBytes, Charsets.US_ASCII)
            when {
                req.startsWith("GET /Scan/Status") ->
                    chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>Idle</ScannerState></ScannerStatus>")
                req.startsWith("POST /Scan/Jobs") -> {
                    capturedJobBody = req.substringAfter("\r\n\r\n")
                    chunkedResponse("201 Created", extraHeaders = "Location: /Scan/Jobs/JobList/1\r\n", body = "")
                }
                req.startsWith("GET /Scan/Jobs/JobList/1 ") ->
                    chunkedResponse(
                        "200 OK",
                        body = "<PreScanPage><PageState>ReadyToUpload</PageState>" +
                            "<BinaryURL>/Scan/Jobs/JobList/1/Pages/1/Image</BinaryURL></PreScanPage>",
                    )
                req.startsWith("GET /Scan/Jobs/JobList/1/Pages/1/Image") ->
                    binaryChunkedResponse("200 OK", fakeJpeg)
                else -> throw IOException("unexpected request: $req")
            }
        }

        val output = createTempFile().toFile()
        ScanPipeline(transport, pollDelayMs = 0).scan(output, resolution = 600, colorMode = ScanColorMode.GRAYSCALE)

        assertTrue(capturedJobBody.contains("<XResolution>600</XResolution>"))
        assertTrue(capturedJobBody.contains("<YResolution>600</YResolution>"))
        assertTrue(capturedJobBody.contains("<ColorSpace>Gray</ColorSpace>"))
    }
```

- [ ] **Step 7: Run to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.ScanPipelineTest"`
Expected: FAIL — `scan()` doesn't accept `resolution`/`colorMode` parameters yet.

- [ ] **Step 8: Widen `ScanPipeline.scan()`**

In `ScanPipeline.kt`, replace:

```kotlin
    fun scan(output: File) {
        val statusReader = send(LedmRequests.statusRequest(host))
        ChunkedHttp.readHeader(statusReader)
        val statusBody = String(ChunkedHttp.readChunkedBody(statusReader), Charsets.US_ASCII)
        val state = LedmResponses.parseScannerState(statusBody)
        if (state != ScannerState.IDLE) throw IOException("Scanner not idle: $state")

        val jobBody = LedmRequests.createJobBody(
            DEFAULT_RESOLUTION, DEFAULT_RESOLUTION, 0, DEFAULT_WIDTH, 0, DEFAULT_HEIGHT,
        )
```

with:

```kotlin
    fun scan(output: File, resolution: Int = DEFAULT_RESOLUTION, colorMode: ScanColorMode = ScanColorMode.COLOR) {
        val statusReader = send(LedmRequests.statusRequest(host))
        ChunkedHttp.readHeader(statusReader)
        val statusBody = String(ChunkedHttp.readChunkedBody(statusReader), Charsets.US_ASCII)
        val state = LedmResponses.parseScannerState(statusBody)
        if (state != ScannerState.IDLE) throw IOException("Scanner not idle: $state")

        val colorSpace = when (colorMode) {
            ScanColorMode.COLOR -> "Color"
            ScanColorMode.GRAYSCALE -> "Gray"
        }
        val jobBody = LedmRequests.createJobBody(
            resolution, resolution, 0, DEFAULT_WIDTH, 0, DEFAULT_HEIGHT, colorSpace,
        )
```

(Everything below this point in `scan()` is unchanged — `jobBody` is still consumed the
same way immediately afterward.)

- [ ] **Step 9: Run the `ScanPipeline` tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.ScanPipelineTest"`
Expected: PASS (all tests, including the new one)

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/scan/ScanColorMode.kt app/src/main/java/dev/jaspreet/printserver/scan/LedmRequests.kt app/src/main/java/dev/jaspreet/printserver/scan/ScanPipeline.kt app/src/test/java/dev/jaspreet/printserver/scan/LedmRequestsTest.kt app/src/test/java/dev/jaspreet/printserver/scan/ScanPipelineTest.kt
git commit -m "feat: make ScanPipeline's resolution and color mode configurable"
```

---

### Task 2: `LedmCapabilities` — live scanner capability query

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/scan/LedmCapabilities.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/scan/LedmCapabilitiesTest.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/scan/LedmRequests.kt`
- Modify: `app/src/test/java/dev/jaspreet/printserver/scan/LedmRequestsTest.kt`

Mirrors Tier 1's `PrinterQuery` (live capability query) rather than Tier 2 print's
hardcoded `PrinterCapabilities`. The parsing logic here ports `bb_ledm.c`'s
`parse_scan_elements` function (read during Spec A's research): `<Platen>` contains
min/max width/height then a `<SupportedResolutions>` block of repeated
`<Resolution>` tags; `<ColorEntries>` contains repeated `<ColorType>` tags whose text
is one of `K1`/`Gray8`/`Color8`.

- [ ] **Step 1: Add the `GET /Scan/ScanCaps` request builder**

Write the failing test first, add to `LedmRequestsTest.kt`:

```kotlin
    @Test
    fun `builds the scan capabilities request`() {
        val req = LedmRequests.scanCapsRequest("localhost")
        assertEquals(
            "GET /Scan/ScanCaps HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "User-Agent: hplip\r\n" +
                "Accept: text/xml\r\n" +
                "Accept-Language: en-us,en\r\n" +
                "Accept-Charset:utf-8\r\n" +
                "Keep-Alive: 20\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "Cookie: AccessCounter=new\r\n0\r\n\r\n",
            req,
        )
    }
```

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.LedmRequestsTest"`
Expected: FAIL — `scanCapsRequest` doesn't exist yet.

Add to `LedmRequests.kt`, right after `statusRequest`:

```kotlin
    fun scanCapsRequest(host: String): String =
        "GET /Scan/ScanCaps HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: hplip\r\n" +
            "Accept: text/xml\r\n" +
            "Accept-Language: en-us,en\r\n" +
            "Accept-Charset:utf-8\r\n" +
            "Keep-Alive: 20\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Cookie: AccessCounter=new" +
            ZERO_FOOTER
```

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.LedmRequestsTest"`
Expected: PASS

- [ ] **Step 2: Write the failing tests for parsing `ScanCaps` XML**

```kotlin
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
```

- [ ] **Step 3: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.LedmCapabilitiesTest"`
Expected: FAIL — `LedmCapabilities` doesn't exist yet.

- [ ] **Step 4: Create `LedmCapabilities.kt`**

```kotlin
package dev.jaspreet.printserver.scan

import dev.jaspreet.printserver.usb.UsbTransport

data class ScannerCapabilities(
    val maxWidth: Int,
    val maxHeight: Int,
    val supportedResolutions: List<Int>,
    val supportedColorModes: Set<ScanColorMode>,
)

/** Live-queries the scanner's real capabilities via GET /Scan/ScanCaps -- mirrors Tier
 *  1's PrinterQuery pattern (query real hardware) rather than Tier 2 print's hardcoded
 *  PrinterCapabilities, since this device's capabilities genuinely are queryable.
 *  Ports bb_ledm.c's parse_scan_elements() function (read during Spec A's research):
 *  <Platen> holds min/max width/height and a <SupportedResolutions> block of repeated
 *  <Resolution> tags; <ColorEntries> holds repeated <ColorType> tags. */
object LedmCapabilities {

    /** Queries the live device over [transport] and parses its response. */
    fun query(transport: UsbTransport, host: String = "localhost"): ScannerCapabilities {
        val requestBytes = LedmRequests.scanCapsRequest(host).toByteArray(Charsets.UTF_8)
        transport.write(requestBytes, 0, requestBytes.size)
        val reader = PullReader {
            val buf = ByteArray(16384)
            val n = transport.read(buf)
            buf.copyOf(n)
        }
        ChunkedHttp.readHeader(reader)
        val body = String(ChunkedHttp.readChunkedBody(reader), Charsets.US_ASCII)
        return parse(body)
    }

    /** Pure parsing of an already-fetched ScanCaps XML body -- the seam JVM tests exercise. */
    fun parse(xml: String): ScannerCapabilities {
        val platen = xml.substringAfter("<Platen>", "").substringBefore("</Platen>")
        val maxWidth = tagInt(platen, "MaxWidth") ?: 0
        val maxHeight = tagInt(platen, "MaxHeight") ?: 0
        val resolutions = Regex("<Resolution>(\\d+)</Resolution>").findAll(platen)
            .map { it.groupValues[1].toInt() }
            .toList()

        val colorEntries = xml.substringAfter("<ColorEntries>", "").substringBefore("</ColorEntries>")
        val colorModes = Regex("<ColorType>(\\w+)</ColorType>").findAll(colorEntries)
            .mapNotNull {
                when (it.groupValues[1]) {
                    "Color8" -> ScanColorMode.COLOR
                    "Gray8" -> ScanColorMode.GRAYSCALE
                    else -> null // K1 (1-bit) -- not modeled, see ScanColorMode's doc comment
                }
            }
            .toSet()

        return ScannerCapabilities(maxWidth, maxHeight, resolutions, colorModes)
    }

    private fun tagInt(xml: String, tag: String): Int? =
        Regex("<$tag>(\\d+)</$tag>").find(xml)?.groupValues?.get(1)?.toIntOrNull()
}
```

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.LedmCapabilitiesTest"`
Expected: PASS (all 4 tests)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/scan/LedmCapabilities.kt app/src/test/java/dev/jaspreet/printserver/scan/LedmCapabilitiesTest.kt app/src/main/java/dev/jaspreet/printserver/scan/LedmRequests.kt app/src/test/java/dev/jaspreet/printserver/scan/LedmRequestsTest.kt
git commit -m "feat: add live scanner capability query (LedmCapabilities)"
```

---

### Task 3: `EsclXml` — eSCL wire-format translation

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/escl/EsclXml.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/escl/EsclXmlTest.kt`

Pure functions: no I/O. See this plan's top-level note on eSCL XML fidelity — these
shapes follow well-known public eSCL/Mopria conventions, validated for real in Task 6.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScannerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
```

(Add `import org.junit.Assert.assertFalse` and `import org.junit.Assert.assertTrue`
alongside the other JUnit imports at the top of the file.)

- [ ] **Step 2: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.escl.EsclXmlTest"`
Expected: FAIL — `EsclXml`, `EsclJobInfo` don't exist yet.

- [ ] **Step 3: Create `EsclXml.kt`**

```kotlin
package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScannerCapabilities

data class EsclScanSettings(val resolution: Int?, val colorMode: ScanColorMode?)

data class EsclJobInfo(val id: String, val state: String)

/** Builds/parses eSCL's XML wire format. See this plan's top-level note on fidelity --
 *  these shapes follow public Mopria/AirScan eSCL conventions, validated against a real
 *  client in this plan's hardware-test task. */
object EsclXml {
    private const val SCAN_NS = "http://schemas.hp.com/imaging/escl/2011/05/03"
    private const val PWG_NS = "http://www.pwg.org/schemas/2010/12/sm"

    fun scannerCapabilities(caps: ScannerCapabilities, makeAndModel: String): String {
        val colorModes = buildString {
            if (ScanColorMode.COLOR in caps.supportedColorModes) append("<scan:ColorMode>RGB24</scan:ColorMode>")
            if (ScanColorMode.GRAYSCALE in caps.supportedColorModes) append("<scan:ColorMode>Grayscale8</scan:ColorMode>")
        }
        val resolutions = caps.supportedResolutions.joinToString("") { dpi ->
            "<scan:DiscreteResolution><scan:XResolution>$dpi</scan:XResolution>" +
                "<scan:YResolution>$dpi</scan:YResolution></scan:DiscreteResolution>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<scan:ScannerCapabilities xmlns:scan=\"$SCAN_NS\" xmlns:pwg=\"$PWG_NS\">" +
            "<pwg:Version>2.63</pwg:Version>" +
            "<pwg:MakeAndModel>$makeAndModel</pwg:MakeAndModel>" +
            "<scan:Platen><scan:PlatenInputCaps>" +
            "<scan:MinWidth>50</scan:MinWidth><scan:MinHeight>50</scan:MinHeight>" +
            "<scan:MaxWidth>${caps.maxWidth}</scan:MaxWidth><scan:MaxHeight>${caps.maxHeight}</scan:MaxHeight>" +
            "<scan:MaxScanRegions>1</scan:MaxScanRegions>" +
            "<scan:SettingProfiles><scan:SettingProfile>" +
            "<scan:ColorModes>$colorModes</scan:ColorModes>" +
            "<scan:DocumentFormats><pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat></scan:DocumentFormats>" +
            "<scan:SupportedResolutions><scan:DiscreteResolutions>$resolutions</scan:DiscreteResolutions></scan:SupportedResolutions>" +
            "</scan:SettingProfile></scan:SettingProfiles>" +
            "</scan:PlatenInputCaps></scan:Platen>" +
            "</scan:ScannerCapabilities>"
    }

    fun parseScanSettings(xml: String): EsclScanSettings {
        val resolution = Regex("<scan:XResolution>(\\d+)</scan:XResolution>").find(xml)
            ?.groupValues?.get(1)?.toIntOrNull()
        val colorMode = when {
            xml.contains("<scan:ColorMode>RGB24</scan:ColorMode>") -> ScanColorMode.COLOR
            xml.contains("<scan:ColorMode>Grayscale8</scan:ColorMode>") -> ScanColorMode.GRAYSCALE
            else -> null
        }
        return EsclScanSettings(resolution, colorMode)
    }

    fun scannerStatus(jobs: List<EsclJobInfo>): String {
        val state = if (jobs.any { it.state == "Processing" }) "Processing" else "Idle"
        val jobEntries = jobs.joinToString("") { job ->
            "<scan:JobInfo><pwg:JobUri>/eSCL/ScanJobs/${job.id}</pwg:JobUri>" +
                "<pwg:JobState>${job.state}</pwg:JobState></scan:JobInfo>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<scan:ScannerStatus xmlns:scan=\"$SCAN_NS\" xmlns:pwg=\"$PWG_NS\">" +
            "<pwg:Version>2.63</pwg:Version>" +
            "<scan:State>$state</scan:State>" +
            "<scan:Jobs>$jobEntries</scan:Jobs>" +
            "</scan:ScannerStatus>"
    }
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.escl.EsclXmlTest"`
Expected: PASS (all 6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/escl/EsclXml.kt app/src/test/java/dev/jaspreet/printserver/escl/EsclXmlTest.kt
git commit -m "feat: add eSCL XML request/response translation"
```

---

### Task 4: `LocalEsclServer` — HTTP server and job tracking

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/escl/LocalEsclServer.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/escl/LocalEsclServerTest.kt`

Structurally mirrors `LocalIppServer`: own `ServerSocket`, own accept-loop, semaphore-
bounded connections, reuses `HttpHead`/`BodyReader`. Because this hardware supports only
one physical scan at a time, job tracking is a single-slot `AtomicReference`, not a full
queue like print's `JobQueue` -- a `POST` while a job is already in flight is rejected,
matching the real hardware's exclusivity.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScannerCapabilities
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalEsclServerTest {
    private var server: LocalEsclServer? = null

    @After
    fun tearDown() { server?.stop() }

    private fun start(
        capabilities: ScannerCapabilities = ScannerCapabilities(
            maxWidth = 2550, maxHeight = 3300,
            supportedResolutions = listOf(300), supportedColorModes = setOf(ScanColorMode.COLOR),
        ),
        onScan: (resolution: Int, colorMode: ScanColorMode, output: java.io.File) -> Unit = { _, _, output ->
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte()))
        },
    ): Int {
        val s = LocalEsclServer(
            port = 0, makeAndModel = "PrintServer Scanner", capabilities = capabilities,
            spoolDir = createTempDir(), performScan = onScan,
        )
        s.start(bindAddress = null)
        server = s
        return s.actualPort
    }

    private fun httpGet(port: Int, path: String): Pair<Int, String> {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            val text = BufferedReader(InputStreamReader(socket.getInputStream())).readText()
            val status = text.substringAfter("HTTP/1.1 ").substringBefore(" ").trim().toInt()
            val body = text.substringAfter("\r\n\r\n")
            return status to body
        }
    }

    private fun httpPost(port: Int, path: String, body: String): Pair<Int, String> {
        Socket("127.0.0.1", port).use { socket ->
            val bytes = body.toByteArray()
            socket.getOutputStream().write(
                ("POST $path HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$body")
                    .toByteArray(),
            )
            val text = BufferedReader(InputStreamReader(socket.getInputStream())).readText()
            val status = text.substringAfter("HTTP/1.1 ").substringBefore(" ").trim().toInt()
            val locationHeader = text.lineSequence().firstOrNull { it.startsWith("Location:", ignoreCase = true) }
                ?.substringAfter(":")?.trim() ?: ""
            return status to locationHeader
        }
    }

    @Test
    fun `serves ScannerCapabilities reflecting the live-queried capabilities`() {
        val port = start()
        val (status, body) = httpGet(port, "/eSCL/ScannerCapabilities")
        assertEquals(200, status)
        assertTrue(body.contains("<scan:MaxWidth>2550</scan:MaxWidth>"))
        assertTrue(body.contains("RGB24"))
    }

    @Test
    fun `serves ScannerStatus as Idle before any job is submitted`() {
        val port = start()
        val (status, body) = httpGet(port, "/eSCL/ScannerStatus")
        assertEquals(200, status)
        assertTrue(body.contains("<scan:State>Idle</scan:State>"))
    }

    @Test
    fun `POST ScanJobs starts a scan and returns a Location header`() {
        val done = CountDownLatch(1)
        val port = start(onScan = { _, _, output ->
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            done.countDown()
        })
        val (status, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertEquals(201, status)
        assertTrue(location.startsWith("/eSCL/ScanJobs/"))
        assertTrue("scan should complete", done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `NextDocument serves the scanned bytes once the job completes`() {
        val port = start(onScan = { _, _, output -> output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)) })
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        // Poll ScannerStatus until the job is no longer Processing -- the scan callback
        // above returns immediately, but the server processes it on a background thread.
        var attempts = 20
        while (attempts-- > 0) {
            val (_, statusBody) = httpGet(port, "/eSCL/ScannerStatus")
            if (!statusBody.contains("Processing")) break
            Thread.sleep(50)
        }
        val (docStatus, _) = httpGet(port, "$location/NextDocument")
        assertEquals(200, docStatus)
    }

    @Test
    fun `a second POST while a job is in flight is rejected`() {
        val holdLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val port = start(onScan = { _, _, output ->
            holdLatch.countDown()
            releaseLatch.await(5, TimeUnit.SECONDS)
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        })
        httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertTrue(holdLatch.await(5, TimeUnit.SECONDS))
        val (secondStatus, _) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertEquals(503, secondStatus)
        releaseLatch.countDown()
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.escl.LocalEsclServerTest"`
Expected: FAIL — `LocalEsclServer` doesn't exist yet.

- [ ] **Step 3: Create `LocalEsclServer.kt`**

```kotlin
package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.http.BodyReader
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScannerCapabilities
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private class EsclJob(val id: String, val outputFile: File) {
    @Volatile var state: String = "Processing" // "Processing" | "Completed" | "Aborted"
}

/**
 * A synthetic eSCL scanner: the network-facing sibling of LocalIppServer. Because this
 * hardware supports exactly one scan at a time, [currentJob] is a single slot, not a
 * queue -- a second POST while one is in flight is rejected with 503, matching the real
 * scanner's exclusivity.
 *
 * [performScan] is injected (rather than calling ScanPipeline directly) so JVM tests can
 * substitute a fake scan outcome without real USB hardware -- production wiring (Task 7)
 * passes a lambda that calls the real ScanPipeline against a real UsbTransport.
 */
class LocalEsclServer(
    private val port: Int,
    private val makeAndModel: String,
    private val capabilities: ScannerCapabilities,
    private val spoolDir: File,
    private val performScan: (resolution: Int, colorMode: ScanColorMode, output: File) -> Unit,
    private val maxConcurrentClients: Int = 64,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val clientSlots = Semaphore(maxConcurrentClients)
    private val currentJob = AtomicReference<EsclJob?>(null)
    private val nextJobId = AtomicInteger(1)

    val actualPort: Int get() = serverSocket?.localPort ?: port

    fun start(bindAddress: InetAddress?) {
        val ss = ServerSocket(port, 50, bindAddress)
        serverSocket = ss
        executor.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                if (!clientSlots.tryAcquire()) {
                    try { client.close() } catch (_: IOException) {}
                    continue
                }
                executor.execute {
                    try { handleClient(client) } finally { clientSlots.release() }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 30_000
        client.use {
            val cin = BufferedInputStream(client.getInputStream())
            val cout = BufferedOutputStream(client.getOutputStream())
            val head = try { HttpHead.parse(cin) ?: return } catch (_: IOException) { return }
            val (method, path) = parseStartLine(head.startLine) ?: return respond(cout, 400, "text/plain", "Bad Request")
            val body = try { BodyReader.readAll(head, cin) } catch (_: IOException) { ByteArray(0) }

            when {
                method == "GET" && path == "/eSCL/ScannerCapabilities" ->
                    respond(cout, 200, "text/xml", EsclXml.scannerCapabilities(capabilities, makeAndModel))
                method == "GET" && path == "/eSCL/ScannerStatus" ->
                    respond(cout, 200, "text/xml", EsclXml.scannerStatus(currentJobInfo()))
                method == "POST" && path == "/eSCL/ScanJobs" ->
                    handleCreateJob(cout, body)
                method == "GET" && path.startsWith("/eSCL/ScanJobs/") && path.endsWith("/NextDocument") ->
                    handleNextDocument(cout, path)
                method == "DELETE" && path.startsWith("/eSCL/ScanJobs/") ->
                    handleDeleteJob(cout, path)
                else -> respond(cout, 404, "text/plain", "Not Found")
            }
        }
    }

    private fun handleCreateJob(cout: BufferedOutputStream, body: ByteArray) {
        val settings = EsclXml.parseScanSettings(String(body, Charsets.UTF_8))
        val resolution = settings.resolution?.takeIf { it in capabilities.supportedResolutions }
            ?: capabilities.supportedResolutions.firstOrNull() ?: 300
        val colorMode = settings.colorMode?.takeIf { it in capabilities.supportedColorModes }
            ?: capabilities.supportedColorModes.firstOrNull() ?: ScanColorMode.COLOR

        spoolDir.mkdirs()
        val id = nextJobId.getAndIncrement().toString()
        val output = File(spoolDir, "escl-job-$id.jpg")
        val job = EsclJob(id, output)
        if (!currentJob.compareAndSet(null, job)) {
            respond(cout, 503, "text/plain", "Scanner busy")
            return
        }
        executor.execute {
            try {
                performScan(resolution, colorMode, output)
                job.state = "Completed"
            } catch (e: Exception) {
                job.state = "Aborted"
            } finally {
                currentJob.set(null)
            }
        }
        respondWithHeaders(cout, 201, "text/plain", "", mapOf("Location" to "/eSCL/ScanJobs/$id"))
    }

    private fun handleNextDocument(cout: BufferedOutputStream, path: String) {
        val id = path.removePrefix("/eSCL/ScanJobs/").removeSuffix("/NextDocument")
        val job = currentJob.get()
        val output = if (job?.id == id) job.outputFile else File(spoolDir, "escl-job-$id.jpg")
        if (!output.exists() || (job != null && job.id == id && job.state != "Completed")) {
            respond(cout, 404, "text/plain", "Not ready")
            return
        }
        respondWithHeaders(cout, 200, "image/jpeg", "", emptyMap(), bodyBytes = output.readBytes())
    }

    private fun handleDeleteJob(cout: BufferedOutputStream, path: String) {
        val id = path.removePrefix("/eSCL/ScanJobs/")
        val job = currentJob.get()
        if (job?.id == id) currentJob.set(null)
        respond(cout, 200, "text/plain", "")
    }

    private fun currentJobInfo(): List<EsclJobInfo> =
        currentJob.get()?.let { listOf(EsclJobInfo(it.id, it.state)) } ?: emptyList()

    private fun parseStartLine(startLine: String): Pair<String, String>? {
        val parts = startLine.split(" ")
        if (parts.size < 2) return null
        return parts[0] to parts[1]
    }

    private fun respond(cout: BufferedOutputStream, status: Int, contentType: String, body: String) =
        respondWithHeaders(cout, status, contentType, body, emptyMap())

    private fun respondWithHeaders(
        cout: BufferedOutputStream,
        status: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String>,
        bodyBytes: ByteArray = body.toByteArray(Charsets.UTF_8),
    ) {
        val statusText = when (status) {
            200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"
            404 -> "Not Found"; 503 -> "Service Unavailable"; else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        cout.write(headers.toByteArray(Charsets.ISO_8859_1))
        cout.write(bodyBytes)
        cout.flush()
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.escl.LocalEsclServerTest"`
Expected: PASS (all 5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/escl/LocalEsclServer.kt app/src/test/java/dev/jaspreet/printserver/escl/LocalEsclServerTest.kt
git commit -m "feat: add LocalEsclServer, the network-facing eSCL scanner"
```

---

### Task 5: mDNS `_uscan._tcp` discovery

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/discovery/DiscoveryAdvertiser.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/discovery/NsdAdvertiser.kt`
- Create: `app/src/main/java/dev/jaspreet/printserver/escl/EsclTxtRecords.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/escl/EsclTxtRecordsTest.kt`

- [ ] **Step 1: Add `advertiseEscl` to the `DiscoveryAdvertiser` interface**

In `DiscoveryAdvertiser.kt`, add a new method alongside the existing ones:

```kotlin
package dev.jaspreet.printserver.discovery

interface DiscoveryAdvertiser {
    fun advertiseIpp(name: String, port: Int, txt: Map<String, String>)
    fun advertiseRaw(name: String, port: Int)
    fun advertiseEscl(name: String, port: Int, txt: Map<String, String>)
    /** Withdraw all advertisements (network change, printer unplug, shutdown). */
    fun stopAll()
}
```

- [ ] **Step 2: Implement it in `NsdAdvertiser`**

In `NsdAdvertiser.kt`, add a new method alongside `advertiseIpp`:

```kotlin
    override fun advertiseEscl(name: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_uscan._tcp"
            setPort(port)
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        register(info)
    }
```

- [ ] **Step 3: Write the failing test for the eSCL TXT record shape**

```kotlin
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
```

- [ ] **Step 4: Run to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.escl.EsclTxtRecordsTest"`
Expected: FAIL — `EsclTxtRecords` doesn't exist yet.

- [ ] **Step 5: Create `EsclTxtRecords.kt`**

```kotlin
package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScannerCapabilities

/** DNS-SD TXT records for the _uscan._tcp (eSCL) advertisement. */
object EsclTxtRecords {
    fun forEscl(capabilities: ScannerCapabilities, makeAndModel: String): Map<String, String> = mapOf(
        "txtvers" to "1",
        "ty" to makeAndModel,
        "rs" to "t", // "rs" (representation string) -- eSCL root resource path is /eSCL, "t" signals top-level
        "pdl" to "image/jpeg",
        "vers" to "2.63",
        "note" to "",
    )
}
```

**Note this task's fidelity caveat too**: the exact TXT key set/values real AirScan
clients expect (`rs`'s correct value in particular) should be cross-checked once a real
client is available in Task 6 — fix based on what the client actually requires, not
further guessing.

- [ ] **Step 6: Run the test to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.escl.EsclTxtRecordsTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/discovery/DiscoveryAdvertiser.kt app/src/main/java/dev/jaspreet/printserver/discovery/NsdAdvertiser.kt app/src/main/java/dev/jaspreet/printserver/escl/EsclTxtRecords.kt app/src/test/java/dev/jaspreet/printserver/escl/EsclTxtRecordsTest.kt
git commit -m "feat: advertise the scanner over mDNS _uscan._tcp"
```

---

### Task 6: Wire into `ServerService`

**Files:**
- Modify: `app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt`

This task's exact edit points depend on reading the current, real state of
`ServerService.kt`'s Tier 2 startup path (the code around where `LocalIppServer` is
constructed and `advertiseIpp` is called) — that file is long and has evolved across
many prior plans, so this step is intentionally a guided read-and-integrate task rather
than a verbatim before/after diff.

- [ ] **Step 1: Read the current Tier 2 startup path**

Open `ServerService.kt` and find the function that constructs `LocalIppServer` and calls
`advertiser?.advertiseIpp(...)` (the Tier 2 branch of server startup). Note the exact
local variable names for the connected `UsbDevice`, the resolved Wi-Fi bind address, and
where `caps`/`queue` are constructed — this task's additions live right alongside them.

- [ ] **Step 2: Add scan-side fields alongside the existing print-side ones**

Near the existing `private var localIppServer: LocalIppServer? = null` field, add:

```kotlin
    private var localEsclServer: LocalEsclServer? = null
```

(with the corresponding import: `import dev.jaspreet.printserver.escl.LocalEsclServer`,
`import dev.jaspreet.printserver.escl.EsclTxtRecords`,
`import dev.jaspreet.printserver.scan.ScanPipeline`,
`import dev.jaspreet.printserver.scan.LedmCapabilities`,
`import dev.jaspreet.printserver.usb.UsbTransport` — the last one is likely already
imported, check before duplicating).

- [ ] **Step 3: Open the scan transport and start `LocalEsclServer`**

In the same startup function that opens the Tier 2 print transport and starts
`LocalIppServer`, add (adjust variable names to match what Step 1 found — `device` for
the connected `UsbDevice`, `manager` for the `UsbPrinterManager`, `bindAddr` for the
resolved bind `InetAddress`):

```kotlin
        val scanTransport = manager.openScanTransport(device)
        if (scanTransport != null) {
            val liveCapabilities = try {
                LedmCapabilities.query(scanTransport)
            } catch (e: Exception) {
                Log.w(TAG, "ScanCaps query failed, scan server not started: ${e.message}")
                null
            }
            if (liveCapabilities != null) {
                val escl = LocalEsclServer(
                    port = ESCL_PORT,
                    makeAndModel = caps.makeAndModel,
                    capabilities = liveCapabilities,
                    spoolDir = spoolDir,
                    performScan = { resolution, colorMode, output ->
                        ScanPipeline(scanTransport).scan(output, resolution, colorMode)
                    },
                ).also { localEsclServer = it }
                escl.start(bindAddr)
                advertiser?.advertiseEscl(
                    caps.makeAndModel, ESCL_PORT, EsclTxtRecords.forEscl(liveCapabilities, caps.makeAndModel),
                )
            }
        }
```

Add the new port constant near the existing `IPP_PORT`/`RAW_PORT` constants:

```kotlin
        private const val ESCL_PORT = 8632
```

**Note:** this reuses the single `scanTransport` connection for both the one-time
`LedmCapabilities.query` and every subsequent `ScanPipeline.scan(...)` call inside
`performScan` — since only one scan can be in flight at a time (`LocalEsclServer`'s
single-job-slot enforcement from Task 4), this is safe: the capability query completes
and returns before the server even starts accepting connections, and no two
`ScanPipeline.scan()` calls can run concurrently on the same transport.

- [ ] **Step 4: Stop the eSCL server in the existing teardown path**

Find `stopPipeline()` (or whatever the current Tier 2 shutdown function is named — Step 1
already located it) and add, alongside the existing `ippServer?.stop(); ippServer = null`
line:

```kotlin
        localEsclServer?.stop(); localEsclServer = null
```

- [ ] **Step 5: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/service/ServerService.kt
git commit -m "feat: wire LocalEsclServer into ServerService's Tier 2 startup/teardown"
```

---

### Task 7: Hardware verification

**Files:**
- Modify: `docs/superpowers/testing/hardware-smoke-checklist.md`

Unlike Tasks 1-6, this is where the eSCL XML fidelity caveat (noted at the top of this
plan) actually gets resolved — against a real client, not further guessing.

- [ ] **Step 1: Add scan checks to the hardware smoke checklist**

Add a new section to `docs/superpowers/testing/hardware-smoke-checklist.md`, following
that document's existing style (checkbox list, one physical action per line):

```markdown
## eSCL scan server (Spec B)

Prereq: DeskJet 2300-series MFP connected via OTG, server running, a physical page on
the flatbed before each scan check below.

- [ ] macOS: `dns-sd -B _uscan._tcp` lists the scanner within ~5 s.
- [ ] macOS: Image Capture (or Preview's Import from Scanner) discovers the scanner and
      shows its capabilities (resolution/color options) without a driver install.
- [ ] Scan one page at the default settings — output is a valid, correctly-oriented JPEG.
- [ ] Scan at 600dpi if offered — output is visibly higher-resolution than the 300dpi scan.
- [ ] Scan in grayscale — output is genuinely grayscale, not color.
- [ ] Start a scan from one client, then attempt a second scan from another client before
      the first completes — second attempt is rejected/queued sanely, doesn't crash or
      hang the app.
- [ ] Stop and restart the server — scanner disappears from `dns-sd -B _uscan._tcp` and
      reappears within ~5 s of restart.
```

- [ ] **Step 2: Install and run through the checklist**

Run: `./gradlew :app:installDebug`, then work through every item above against the real
device and a real macOS client (or equivalent Linux/Windows eSCL-aware client).

- [ ] **Step 3: If ScannerCapabilities or ScanSettings XML is rejected by the client**

Do not keep guessing at XML shape. Capture the real client's request and this server's
actual response (e.g. via `tcpdump`/Wireshark on the client's network interface, since
this is now real LAN traffic, not USB), compare field-by-field against a known-good
reference (OpenPrinting's `sane-airscan` source is a good one — it's a real, widely-used
eSCL client implementation), and fix `EsclXml.kt`/`EsclTxtRecords.kt` to match exactly
what real clients send/expect.

- [ ] **Step 4: Once passing, commit**

```bash
git add docs/superpowers/testing/hardware-smoke-checklist.md
# If Step 3 required fixing EsclXml.kt or EsclTxtRecords.kt, include those files too.
git commit -m "test: add eSCL scan server checks to the hardware smoke checklist"
```

---

### Task 8: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full JVM unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass — including every test added across Tasks 1-5.

- [ ] **Step 2: Build the full debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Confirm the hardware checklist from Task 7 is fully checked off**

Re-run any items that were fixed mid-checklist (Task 7 Step 3) to confirm the fix holds.

- [ ] **Step 4: Update `CLAUDE.md` and `AGENTS.md`**

Per this repo's convention, update the Architecture section's scan-pipeline bullet
(added by Spec A's plan) to remove the "no network-facing scanning yet" caveat and
describe the new eSCL layer, e.g.: "**eSCL scan server (Spec B)**: `LocalEsclServer`
(network-facing sibling of `LocalIppServer`) serves the standard eSCL endpoints,
translating between eSCL's XML and `ScanPipeline`'s API; `LedmCapabilities` live-queries
the device's real supported resolutions/color modes (mirroring Tier 1's `PrinterQuery`
pattern) rather than hardcoding them; advertised over mDNS `_uscan._tcp` via
`DiscoveryAdvertiser.advertiseEscl`." Make the identical edit to both files.
