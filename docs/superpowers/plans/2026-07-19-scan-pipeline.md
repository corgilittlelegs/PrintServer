# Scan Pipeline (Spec A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reliably trigger a flatbed scan on the HP DeskJet 2300-series MFP over USB and retrieve the resulting JPEG onto disk, proving out the protocol before any network-facing (eSCL) work is built on top of it.

**Architecture:** HP's LEDM scan protocol turns out to be literal HTTP/1.1 (chunked transfer encoding) tunneled over a raw bidirectional bulk USB pipe — the same shape this app already uses for IPP-USB printing. Pure Kotlin throughout: request-building, chunked-response-decoding, and protocol-state-parsing are all pure functions (JVM-unit-testable with literal wire-format strings); only `ScanPipeline` touches real I/O via the existing `UsbTransport` interface, so it's testable with the existing `FakePrinterTransport` fake. No native code, no new third-party dependency.

**Tech Stack:** Kotlin, existing `UsbTransport`/`AndroidUsbTransport`/`UsbPrinterManager` USB abstraction, JUnit.

Spec: `docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md`

---

### Task 1: `ScanUsb` interface detector + `UsbPrinterManager.openScanTransport`

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/usb/ScanUsb.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/usb/ScanUsbTest.kt`
- Modify: `app/src/main/java/dev/jaspreet/printserver/usb/UsbPrinterManager.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.jaspreet.printserver.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanUsbTest {
    @Test
    fun `detects LEDM scan interface descriptor 255-4`() {
        assertTrue(ScanUsb.isLedmScan(255, 4))
        assertFalse(ScanUsb.isLedmScan(255, 204))  // HP's status/control interface, not scan data
        assertFalse(ScanUsb.isLedmScan(7, 1))       // print interface
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.usb.ScanUsbTest"`
Expected: FAIL — `ScanUsb` doesn't exist yet.

- [ ] **Step 3: Create `ScanUsb.kt`**

```kotlin
package dev.jaspreet.printserver.usb

object ScanUsb {
    const val CLASS_VENDOR_SPECIFIC = 255

    /** HP's proprietary LEDM scan-data interface on RAW_MODE MFPs (confirmed against a
     *  real HP DeskJet 2300-series unit via `adb shell dumpsys usb`): vendor-specific
     *  class, subclass 4. Distinct from that same device's other vendor-specific
     *  interface (subclass 204), which is HP's status/control channel, not scan data. */
    fun isLedmScan(interfaceClass: Int, subclass: Int): Boolean =
        interfaceClass == CLASS_VENDOR_SPECIFIC && subclass == 4
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.usb.ScanUsbTest"`
Expected: PASS

- [ ] **Step 5: Add `openScanTransport` to `UsbPrinterManager`**

In `UsbPrinterManager.kt`, immediately after the existing `openLegacyTransport` function
(which reads `fun openLegacyTransport(device: UsbDevice): UsbTransport? = ...`), add:

```kotlin
    /** Opens the first LEDM scan-data interface (255/4), for the scan pipeline. */
    fun openScanTransport(device: UsbDevice): UsbTransport? =
        device.interfaces()
            .firstOrNull { ScanUsb.isLedmScan(it.interfaceClass, it.interfaceSubclass) }
            ?.let { openInterface(device, it) }
```

This reuses the existing private `openInterface(device, iface)` helper unchanged — it
already generically claims whatever interface it's given and wraps its bulk endpoints in
an `AndroidUsbTransport`, so no changes are needed there.

- [ ] **Step 6: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/usb/ScanUsb.kt app/src/test/java/dev/jaspreet/printserver/usb/ScanUsbTest.kt app/src/main/java/dev/jaspreet/printserver/usb/UsbPrinterManager.kt
git commit -m "feat: detect and open the LEDM scan USB interface"
```

---

### Task 2: `LedmRequests` — pure HTTP request builders

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/scan/LedmRequests.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/scan/LedmRequestsTest.kt`

These build the exact wire-format request text HP's own `hplip` client sends (verified
against `scan/sane/bb_ledm.c` in HPLIP 3.24.4 — see the design spec's "Protocol findings"
section for the literal source macros this ports).

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.jaspreet.printserver.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedmRequestsTest {

    @Test
    fun `builds the scanner status request`() {
        val req = LedmRequests.statusRequest("localhost")
        assertEquals(
            "GET /Scan/Status HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "User-Agent: hplip\r\n" +
                "Accept: text/xml\r\n" +
                "Accept-Language: en-us,en\r\n" +
                "Accept-Charset:utf-8\r\n" +
                "Keep-Alive: 20\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "Cookie: AccessCounter=new\r\n" +
                "0\r\n\r\n",
            req,
        )
    }

    @Test
    fun `builds the create-job request header with the given content length`() {
        val req = LedmRequests.createJobHeader("localhost", 321)
        assertTrue(req.startsWith("POST /Scan/Jobs HTTP/1.1\r\n"))
        assertTrue(req.contains("Content-Length: 321\r\n"))
        assertTrue(req.contains("Host: localhost\r\n"))
        assertTrue(req.endsWith("Cache-Control: no-cache\r\n\r\n"))
    }

    @Test
    fun `builds the create-job XML body with the given scan settings`() {
        val body = LedmRequests.createJobBody(
            xResolution = 300, yResolution = 300,
            xStart = 0, width = 2550, yStart = 0, height = 3300,
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
    fun `builds a GET request for an arbitrary job resource path`() {
        val req = LedmRequests.getResourceRequest("/Scan/Jobs/JobList/1", "localhost")
        assertEquals(
            "GET /Scan/Jobs/JobList/1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "User-Agent: hplip\r\n" +
                "Accept: text/plain\r\n" +
                "Accept-Language: en-us,en\r\n" +
                "Accept-Charset:utf-8\r\n" +
                "X-Requested-With: XMLHttpRequest\r\n" +
                "Keep-Alive: 300\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "Cookie: AccessCounter=new\r\n" +
                "0\r\n\r\n",
            req,
        )
    }

    @Test
    fun `the zero footer is the standard chunked terminator`() {
        assertEquals("\r\n0\r\n\r\n", LedmRequests.ZERO_FOOTER)
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.LedmRequestsTest"`
Expected: FAIL — `LedmRequests` doesn't exist yet.

- [ ] **Step 3: Create `LedmRequests.kt`**

```kotlin
package dev.jaspreet.printserver.scan

/**
 * Builds the exact HTTP/1.1 request text for HP's LEDM scan protocol. These requests
 * travel as raw bytes over a bulk USB pipe, not a real socket -- see
 * docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md for the wire-format
 * source (HPLIP 3.24.4's scan/sane/bb_ledm.c).
 */
object LedmRequests {
    /** Standard HTTP/1.1 chunked-transfer-encoding zero-length terminator chunk. */
    const val ZERO_FOOTER = "\r\n0\r\n\r\n"

    fun statusRequest(host: String): String =
        "GET /Scan/Status HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: hplip\r\n" +
            "Accept: text/xml\r\n" +
            "Accept-Language: en-us,en\r\n" +
            "Accept-Charset:utf-8\r\n" +
            "Keep-Alive: 20\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Cookie: AccessCounter=new\r\n" +
            "0\r\n\r\n"

    /** [contentLength] must be the create-job XML body's byte length plus [ZERO_FOOTER]'s
     *  byte length -- this mirrors bb_ledm.c's own (unusual, but firmware-required)
     *  framing exactly: a real Content-Length header alongside a chunked-style zero
     *  footer written as a separate trailing write. */
    fun createJobHeader(host: String, contentLength: Int): String =
        "POST /Scan/Jobs HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: hplip\r\n" +
            "Accept: text/plain, */*\r\n" +
            "Accept-Language: en-us,en\r\n" +
            "Accept-Charset: ISO-8859-1,utf-8\r\n" +
            "Keep-Alive: 1000\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Content-Type: */*; charset=UTF-8\r\n" +
            "X-Requested-With: XMLHttpRequest\r\n" +
            "Content-Length: $contentLength\r\n" +
            "Cookie: AccessCounter=new\r\n" +
            "Pragma: no-cache\r\n" +
            "Cache-Control: no-cache\r\n\r\n"

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

    /** Used both to poll a job's status (path = the Location header's value from the
     *  create-job response) and to fetch the final image (path = the parsed
     *  &lt;BinaryURL&gt; value) -- bb_ledm.c uses the identical request template for both. */
    fun getResourceRequest(path: String, host: String): String =
        "GET $path HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: hplip\r\n" +
            "Accept: text/plain\r\n" +
            "Accept-Language: en-us,en\r\n" +
            "Accept-Charset:utf-8\r\n" +
            "X-Requested-With: XMLHttpRequest\r\n" +
            "Keep-Alive: 300\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Cookie: AccessCounter=new\r\n" +
            "0\r\n\r\n"
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.LedmRequestsTest"`
Expected: PASS (all 5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/scan/LedmRequests.kt app/src/test/java/dev/jaspreet/printserver/scan/LedmRequestsTest.kt
git commit -m "feat: add pure LEDM HTTP request builders"
```

---

### Task 3: `PullReader` + `ChunkedHttp` — pure HTTP/1.1 chunked response decoding

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/scan/PullReader.kt`
- Create: `app/src/main/java/dev/jaspreet/printserver/scan/ChunkedHttp.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/scan/ChunkedHttpTest.kt`

Every LEDM response is real HTTP/1.1: a status line, headers until a blank line, then a
chunked-transfer-encoded body (RFC 7230 §4.1) — confirmed by reading HPLIP's own
`http_read_header`/`http_read_payload` in `scan/sane/http.c`. `PullReader` is a small
buffered reader over "pull more bytes on demand" (a real `UsbTransport.read()` call in
production, a scripted byte queue in tests); `ChunkedHttp` contains the actual
header/chunk-framing logic, built on top of it.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.jaspreet.printserver.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkedHttpTest {

    /** Feeds the whole response as a queue of byte-array pieces, one per `fill()` call,
     *  so tests can exercise both "response arrives in one read" and "response arrives
     *  split across several reads" by varying how the pieces are chunked. */
    private fun readerOver(vararg pieces: ByteArray): PullReader {
        val queue = ArrayDeque(pieces.toList())
        return PullReader { queue.removeFirstOrNull() ?: throw java.io.IOException("exhausted") }
    }

    @Test
    fun `reads header lines up to the blank line, one read`() {
        val whole = "HTTP/1.1 200 OK\r\nLocation: /Scan/Jobs/JobList/1\r\n\r\n".toByteArray()
        val reader = readerOver(whole)
        val header = ChunkedHttp.readHeader(reader)
        assertEquals("HTTP/1.1 200 OK\r\nLocation: /Scan/Jobs/JobList/1\r\n", header)
    }

    @Test
    fun `reads header split across multiple underlying reads`() {
        val reader = readerOver(
            "HTTP/1.1 200".toByteArray(),
            " OK\r\nLocation:".toByteArray(),
            " /Scan/Jobs/JobList/1\r\n\r\n".toByteArray(),
        )
        val header = ChunkedHttp.readHeader(reader)
        assertEquals("HTTP/1.1 200 OK\r\nLocation: /Scan/Jobs/JobList/1\r\n", header)
    }

    @Test
    fun `decodes a single-chunk body`() {
        val body = "<ScannerState>Idle</ScannerState>"
        val whole = ("${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n").toByteArray()
        val reader = readerOver(whole)
        val decoded = ChunkedHttp.readChunkedBody(reader)
        assertArrayEquals(body.toByteArray(), decoded)
    }

    @Test
    fun `decodes a multi-chunk body`() {
        val whole = ("5\r\nHello\r\n" + "6\r\n World\r\n" + "0\r\n\r\n").toByteArray()
        val reader = readerOver(whole)
        val decoded = ChunkedHttp.readChunkedBody(reader)
        assertArrayEquals("Hello World".toByteArray(), decoded)
    }

    @Test
    fun `decodes binary chunk data without treating it as text`() {
        val binaryChunk = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x0A) // JPEG-ish magic + a raw LF byte
        val whole = "${binaryChunk.size.toString(16)}\r\n".toByteArray() + binaryChunk + "\r\n0\r\n\r\n".toByteArray()
        val reader = readerOver(whole)
        val decoded = ChunkedHttp.readChunkedBody(reader)
        assertArrayEquals(binaryChunk, decoded)
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.ChunkedHttpTest"`
Expected: FAIL — `PullReader`/`ChunkedHttp` don't exist yet.

- [ ] **Step 3: Create `PullReader.kt`**

```kotlin
package dev.jaspreet.printserver.scan

import java.io.IOException

/**
 * Minimal pull-based buffered byte reader over something that yields more bytes on
 * demand (a real `UsbTransport.read()` call in production, a scripted queue in tests).
 * Lets callers ask for "one CRLF-terminated line" or "exactly N bytes" without caring
 * how the underlying source happened to chunk its reads.
 */
class PullReader(private val fill: () -> ByteArray) {
    private var buf = ByteArray(0)
    private var pos = 0

    private fun pullMore() {
        val more = fill()
        if (more.isEmpty()) throw IOException("PullReader: source returned no data")
        buf = if (pos == 0) buf + more else buf.copyOfRange(pos, buf.size) + more
        pos = 0
    }

    /** Reads and consumes one CRLF-terminated line; the CRLF itself is not included. */
    fun readLine(): String {
        while (true) {
            var i = pos
            while (i + 1 < buf.size) {
                if (buf[i] == 13.toByte() && buf[i + 1] == 10.toByte()) {
                    val line = String(buf, pos, i - pos, Charsets.US_ASCII)
                    pos = i + 2
                    return line
                }
                i++
            }
            pullMore()
        }
    }

    /** Reads and consumes exactly [n] bytes. */
    fun readExactly(n: Int): ByteArray {
        while (buf.size - pos < n) pullMore()
        val result = buf.copyOfRange(pos, pos + n)
        pos += n
        return result
    }
}
```

- [ ] **Step 4: Create `ChunkedHttp.kt`**

```kotlin
package dev.jaspreet.printserver.scan

import java.io.ByteArrayOutputStream

/** Decodes an HTTP/1.1 response's header and chunked-transfer-encoded body (RFC 7230
 *  §4.1) from a [PullReader]. See docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md
 *  for why LEDM responses are genuinely framed this way. */
object ChunkedHttp {

    /** Reads the status line and headers up to (not including) the blank line. */
    fun readHeader(reader: PullReader): String {
        val header = StringBuilder()
        while (true) {
            val line = reader.readLine()
            if (line.isEmpty()) break
            header.append(line).append("\r\n")
        }
        return header.toString()
    }

    /** Reads a chunked body: repeated (hex-size line, that many bytes, trailing CRLF),
     *  terminated by a zero-size chunk. */
    fun readChunkedBody(reader: PullReader): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = reader.readLine().trim().toInt(16)
            if (size == 0) {
                reader.readLine() // trailing blank line after the zero chunk
                break
            }
            out.write(reader.readExactly(size))
            reader.readLine() // trailing CRLF after this chunk's data
        }
        return out.toByteArray()
    }
}
```

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.ChunkedHttpTest"`
Expected: PASS (all 5 tests)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/scan/PullReader.kt app/src/main/java/dev/jaspreet/printserver/scan/ChunkedHttp.kt app/src/test/java/dev/jaspreet/printserver/scan/ChunkedHttpTest.kt
git commit -m "feat: add pure HTTP/1.1 chunked response decoding for LEDM"
```

---

### Task 4: `LedmResponses` — pure protocol-state parsing

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/scan/LedmResponses.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/scan/LedmResponsesTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.jaspreet.printserver.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedmResponsesTest {

    @Test
    fun `parses scanner state idle`() {
        assertEquals(ScannerState.IDLE, LedmResponses.parseScannerState("<ScannerStatus><ScannerState>Idle</ScannerState></ScannerStatus>"))
    }

    @Test
    fun `parses scanner state busy`() {
        assertEquals(ScannerState.BUSY, LedmResponses.parseScannerState("<ScannerStatus><ScannerState>BusyWithScanJob</ScannerState></ScannerStatus>"))
    }

    @Test
    fun `parses scanner state unknown for anything else`() {
        assertEquals(ScannerState.UNKNOWN, LedmResponses.parseScannerState("<garbage/>"))
    }

    @Test
    fun `parses the Location header value`() {
        val header = "HTTP/1.1 201 Created\r\nLocation: /Scan/Jobs/JobList/1\r\nContent-Length: 0\r\n"
        assertEquals("/Scan/Jobs/JobList/1", LedmResponses.parseLocationHeader(header))
    }

    @Test
    fun `Location header parsing returns null when absent`() {
        assertNull(LedmResponses.parseLocationHeader("HTTP/1.1 400 Bad Request\r\n"))
    }

    @Test
    fun `poll response with no PreScanPage tag is NoDocument`() {
        val result = LedmResponses.parsePollResponse("<Jobs><Job><j:JobState>Processing</j:JobState></Job></Jobs>")
        assertEquals(PollResult.NoDocument, result)
    }

    @Test
    fun `poll response reporting canceled by device is Canceled`() {
        val body = "<PreScanPage><PageState>CanceledByDevice</PageState></PreScanPage>"
        assertEquals(PollResult.Canceled, LedmResponses.parsePollResponse(body))
    }

    @Test
    fun `poll response reporting job canceled is Canceled`() {
        val body = "<PreScanPage><j:JobState>Canceled</j:JobState></PreScanPage>"
        assertEquals(PollResult.Canceled, LedmResponses.parsePollResponse(body))
    }

    @Test
    fun `poll response reporting job completed is Completed`() {
        val body = "<PreScanPage><j:JobState>Completed</j:JobState></PreScanPage>"
        assertEquals(PollResult.Completed, LedmResponses.parsePollResponse(body))
    }

    @Test
    fun `poll response ready to upload extracts the BinaryURL`() {
        val body = "<PreScanPage><PageState>ReadyToUpload</PageState><BinaryURL>/Scan/Jobs/JobList/1/Pages/1/Image</BinaryURL></PreScanPage>"
        val result = LedmResponses.parsePollResponse(body)
        assertTrue(result is PollResult.PageReady)
        assertEquals("/Scan/Jobs/JobList/1/Pages/1/Image", (result as PollResult.PageReady).binaryUrl)
    }

    @Test
    fun `poll response still processing without a terminal state is StillWaiting`() {
        val body = "<PreScanPage><j:JobState>Processing</j:JobState></PreScanPage>"
        assertEquals(PollResult.StillWaiting, LedmResponses.parsePollResponse(body))
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.LedmResponsesTest"`
Expected: FAIL — `LedmResponses`, `ScannerState`, `PollResult` don't exist yet.

- [ ] **Step 3: Create `LedmResponses.kt`**

```kotlin
package dev.jaspreet.printserver.scan

enum class ScannerState { IDLE, BUSY, UNKNOWN }

sealed class PollResult {
    data class PageReady(val binaryUrl: String) : PollResult()
    object Completed : PollResult()
    object Canceled : PollResult()
    object NoDocument : PollResult()
    object StillWaiting : PollResult()
}

/** Parses LEDM protocol XML fragments and HTTP headers. See
 *  docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md for the tag names
 *  this ports (from HPLIP 3.24.4's scan/sane/bb_ledm.c). */
object LedmResponses {

    fun parseScannerState(body: String): ScannerState = when {
        body.contains("<ScannerState>Idle</ScannerState>") -> ScannerState.IDLE
        body.contains("<ScannerState>BusyWithScanJob</ScannerState>") -> ScannerState.BUSY
        else -> ScannerState.UNKNOWN
    }

    /** Null if no Location header is present (e.g. job creation failed). */
    fun parseLocationHeader(header: String): String? {
        val idx = header.indexOf("Location:")
        if (idx < 0) return null
        val start = idx + "Location:".length
        val end = header.indexOf("\r\n", start).let { if (it < 0) header.length else it }
        return header.substring(start, end).trim()
    }

    /** Mirrors bb_ledm.c's bb_start_scan() poll-loop body: these checks are applied, in
     *  this order, to each individual poll response in turn (not accumulated state). */
    fun parsePollResponse(body: String): PollResult = when {
        !body.contains("<PreScanPage>") -> PollResult.NoDocument
        body.contains("<j:JobState>Canceled</j:JobState>") ||
            body.contains("<PageState>CanceledByDevice</PageState>") ||
            body.contains("<PageState>CanceledByClient</PageState>") -> PollResult.Canceled
        body.contains("<j:JobState>Completed</j:JobState>") -> PollResult.Completed
        body.contains("<PageState>ReadyToUpload</PageState>") -> {
            val tag = "<BinaryURL>"
            val start = body.indexOf(tag)
            val end = if (start >= 0) body.indexOf("</BinaryURL>", start) else -1
            if (start >= 0 && end >= 0) PollResult.PageReady(body.substring(start + tag.length, end))
            else PollResult.StillWaiting // malformed; treat as not-ready-yet rather than crash
        }
        else -> PollResult.StillWaiting
    }
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.LedmResponsesTest"`
Expected: PASS (all 10 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/scan/LedmResponses.kt app/src/test/java/dev/jaspreet/printserver/scan/LedmResponsesTest.kt
git commit -m "feat: add pure LEDM response/protocol-state parsing"
```

---

### Task 5: `ScanPipeline` — orchestration

**Files:**
- Create: `app/src/main/java/dev/jaspreet/printserver/scan/ScanPipeline.kt`
- Create: `app/src/test/java/dev/jaspreet/printserver/scan/ScanPipelineTest.kt`

This is the only piece that touches real I/O (via `UsbTransport`), so — like
`JobQueue`/`LocalIppServer` — it's tested here with a fake transport
(`FakePrinterTransport`, which already exists in `app/src/test/java/dev/jaspreet/printserver/usb/FakePrinterTransport.kt`
and is scriptable per-request), not real hardware. Real-hardware verification is Task 6.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.jaspreet.printserver.scan

import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import kotlin.io.path.createTempFile

class ScanPipelineTest {

    /** Wraps a text body in a full HTTP/1.1 chunked response, matching the real wire
     *  format the printer actually sends (status line, blank-line-terminated headers,
     *  one chunk containing the whole body, zero-chunk terminator). */
    private fun chunkedResponse(status: String, extraHeaders: String = "", body: String): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n$extraHeaders\r\n"
        val chunk = bodyBytes.size.toString(16) + "\r\n" + body + "\r\n0\r\n\r\n"
        return (head + chunk).toByteArray(Charsets.UTF_8)
    }

    private fun binaryChunkedResponse(status: String, bytes: ByteArray): ByteArray {
        val head = "HTTP/1.1 $status\r\n\r\n".toByteArray(Charsets.UTF_8)
        val chunkHeader = (bytes.size.toString(16) + "\r\n").toByteArray(Charsets.UTF_8)
        val chunkFooter = "\r\n0\r\n\r\n".toByteArray(Charsets.UTF_8)
        return head + chunkHeader + bytes + chunkFooter
    }

    @Test
    fun `happy path scans a page and writes the JPEG bytes`() {
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())
        var pollCount = 0
        val transport = FakePrinterTransport { reqBytes ->
            val req = String(reqBytes, Charsets.US_ASCII)
            when {
                req.startsWith("GET /Scan/Status") ->
                    chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>Idle</ScannerState></ScannerStatus>")
                req.startsWith("POST /Scan/Jobs") ->
                    chunkedResponse("201 Created", extraHeaders = "Location: /Scan/Jobs/JobList/1\r\n", body = "")
                req.startsWith("GET /Scan/Jobs/JobList/1 ") -> {
                    pollCount++
                    chunkedResponse(
                        "200 OK",
                        body = "<PreScanPage><PageState>ReadyToUpload</PageState>" +
                            "<BinaryURL>/Scan/Jobs/JobList/1/Pages/1/Image</BinaryURL></PreScanPage>",
                    )
                }
                req.startsWith("GET /Scan/Jobs/JobList/1/Pages/1/Image") ->
                    binaryChunkedResponse("200 OK", fakeJpeg)
                else -> throw IOException("unexpected request: $req")
            }
        }

        val output = createTempFile().toFile()
        ScanPipeline(transport, pollDelayMs = 0).scan(output)

        assertArrayEquals(fakeJpeg, output.readBytes())
    }

    @Test
    fun `throws when the scanner reports busy`() {
        val transport = FakePrinterTransport {
            chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>BusyWithScanJob</ScannerState></ScannerStatus>")
        }
        val output = createTempFile().toFile()
        assertThrows(IOException::class.java) { ScanPipeline(transport, pollDelayMs = 0).scan(output) }
    }

    @Test
    fun `throws when no document is on the flatbed`() {
        val transport = FakePrinterTransport { reqBytes ->
            val req = String(reqBytes, Charsets.US_ASCII)
            when {
                req.startsWith("GET /Scan/Status") ->
                    chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>Idle</ScannerState></ScannerStatus>")
                req.startsWith("POST /Scan/Jobs") ->
                    chunkedResponse("201 Created", extraHeaders = "Location: /Scan/Jobs/JobList/1\r\n", body = "")
                req.startsWith("GET /Scan/Jobs/JobList/1 ") ->
                    chunkedResponse("200 OK", body = "<Jobs><Job><j:JobState>Processing</j:JobState></Job></Jobs>")
                else -> throw IOException("unexpected request: $req")
            }
        }
        val output = createTempFile().toFile()
        assertThrows(IOException::class.java) { ScanPipeline(transport, pollDelayMs = 0).scan(output) }
    }

    @Test
    fun `throws when the job is canceled`() {
        val transport = FakePrinterTransport { reqBytes ->
            val req = String(reqBytes, Charsets.US_ASCII)
            when {
                req.startsWith("GET /Scan/Status") ->
                    chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>Idle</ScannerState></ScannerStatus>")
                req.startsWith("POST /Scan/Jobs") ->
                    chunkedResponse("201 Created", extraHeaders = "Location: /Scan/Jobs/JobList/1\r\n", body = "")
                req.startsWith("GET /Scan/Jobs/JobList/1 ") ->
                    chunkedResponse("200 OK", body = "<PreScanPage><PageState>CanceledByDevice</PageState></PreScanPage>")
                else -> throw IOException("unexpected request: $req")
            }
        }
        val output = createTempFile().toFile()
        assertThrows(IOException::class.java) { ScanPipeline(transport, pollDelayMs = 0).scan(output) }
    }

    @Test
    fun `gives up after the configured number of still-waiting polls`() {
        val transport = FakePrinterTransport { reqBytes ->
            val req = String(reqBytes, Charsets.US_ASCII)
            when {
                req.startsWith("GET /Scan/Status") ->
                    chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>Idle</ScannerState></ScannerStatus>")
                req.startsWith("POST /Scan/Jobs") ->
                    chunkedResponse("201 Created", extraHeaders = "Location: /Scan/Jobs/JobList/1\r\n", body = "")
                req.startsWith("GET /Scan/Jobs/JobList/1 ") ->
                    chunkedResponse("200 OK", body = "<PreScanPage><j:JobState>Processing</j:JobState></PreScanPage>")
                else -> throw IOException("unexpected request: $req")
            }
        }
        val output = createTempFile().toFile()
        assertThrows(IOException::class.java) {
            ScanPipeline(transport, pollDelayMs = 0, maxPolls = 3).scan(output)
        }
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.ScanPipelineTest"`
Expected: FAIL — `ScanPipeline` doesn't exist yet.

- [ ] **Step 3: Create `ScanPipeline.kt`**

```kotlin
package dev.jaspreet.printserver.scan

import dev.jaspreet.printserver.usb.UsbTransport
import java.io.File
import java.io.IOException

/**
 * Drives one flatbed scan over an already-opened [UsbTransport] (the LEDM scan
 * interface), writing the resulting JPEG bytes to the given output file. See
 * docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md for the protocol
 * source and for why the default region below is a placeholder pending hardware
 * confirmation (Task 6 of this plan).
 */
class ScanPipeline(
    private val transport: UsbTransport,
    private val host: String = "localhost",
    private val pollDelayMs: Long = 500,
    private val maxPolls: Int = 60, // ~30s of polling at the default delay
) {
    companion object {
        private const val DEFAULT_RESOLUTION = 300
        private const val DEFAULT_WIDTH = 2550  // US Letter width at 300dpi -- placeholder, see Task 6
        private const val DEFAULT_HEIGHT = 3300 // US Letter height at 300dpi -- placeholder, see Task 6
        private const val READ_CHUNK = 16384
    }

    fun scan(output: File) {
        val statusReader = send(LedmRequests.statusRequest(host))
        ChunkedHttp.readHeader(statusReader)
        val statusBody = String(ChunkedHttp.readChunkedBody(statusReader), Charsets.US_ASCII)
        val state = LedmResponses.parseScannerState(statusBody)
        if (state != ScannerState.IDLE) throw IOException("Scanner not idle: $state")

        val jobBody = LedmRequests.createJobBody(
            DEFAULT_RESOLUTION, DEFAULT_RESOLUTION, 0, DEFAULT_WIDTH, 0, DEFAULT_HEIGHT,
        )
        val jobBodyBytes = jobBody.toByteArray(Charsets.UTF_8)
        val footerBytes = LedmRequests.ZERO_FOOTER.toByteArray(Charsets.UTF_8)
        val jobHeader = LedmRequests.createJobHeader(host, jobBodyBytes.size + footerBytes.size)
        val createReader = send(jobHeader.toByteArray(Charsets.UTF_8), jobBodyBytes, footerBytes)
        val createHeader = ChunkedHttp.readHeader(createReader)
        ChunkedHttp.readChunkedBody(createReader) // body unused, only the Location header matters
        val jobUrl = LedmResponses.parseLocationHeader(createHeader)
            ?: throw IOException("No Location header in create-job response")

        var pollsLeft = maxPolls
        while (true) {
            val pollReader = send(LedmRequests.getResourceRequest(jobUrl, host))
            ChunkedHttp.readHeader(pollReader)
            val pollBody = String(ChunkedHttp.readChunkedBody(pollReader), Charsets.US_ASCII)
            when (val result = LedmResponses.parsePollResponse(pollBody)) {
                is PollResult.PageReady -> {
                    val binReader = send(LedmRequests.getResourceRequest(result.binaryUrl, host))
                    ChunkedHttp.readHeader(binReader)
                    output.writeBytes(ChunkedHttp.readChunkedBody(binReader))
                    return
                }
                PollResult.Completed -> throw IOException("Job completed with no page produced")
                PollResult.Canceled -> throw IOException("Scan job was canceled")
                PollResult.NoDocument -> throw IOException("No document detected on the flatbed")
                PollResult.StillWaiting -> {
                    if (--pollsLeft <= 0) throw IOException("Timed out waiting for scan to become ready")
                    Thread.sleep(pollDelayMs)
                }
            }
        }
    }

    /** Writes each of [parts] in order, then returns a [PullReader] over the response. */
    private fun send(vararg parts: ByteArray): PullReader {
        for (part in parts) transport.write(part, 0, part.size)
        return PullReader {
            val buf = ByteArray(READ_CHUNK)
            val n = transport.read(buf)
            buf.copyOf(n)
        }
    }

    private fun send(request: String): PullReader = send(request.toByteArray(Charsets.UTF_8))
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.scan.ScanPipelineTest"`
Expected: PASS (all 5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/jaspreet/printserver/scan/ScanPipeline.kt app/src/test/java/dev/jaspreet/printserver/scan/ScanPipelineTest.kt
git commit -m "feat: add ScanPipeline orchestrating the LEDM scan sequence"
```

---

### Task 6: Device androidTest — real hardware verification

**Files:**
- Create: `app/src/androidTest/java/dev/jaspreet/printserver/ScanPipelineHardwareTest.kt`

Unlike Tasks 1-5, this cannot be fully specified in advance: the exact `Host` header
value the firmware expects, and the correct platen region units (the `XStart`/`Width`/
`YStart`/`Height` values in `ScanPipeline`'s XML body), are unconfirmed against real
hardware per the design spec's explicit callouts. This task is where that gets resolved
empirically, not guessed.

- [ ] **Step 1: Write the test against the current placeholder defaults**

```kotlin
package dev.jaspreet.printserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jaspreet.printserver.scan.ScanPipeline
import dev.jaspreet.printserver.usb.UsbPrinterManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-only, requires the real DeskJet 2300-series MFP connected via USB with a
 *  physical page placed on the flatbed before running. Confirms the LEDM scan pipeline
 *  end-to-end against real firmware -- see docs/superpowers/plans/2026-07-19-scan-pipeline.md
 *  Task 6 for how to interpret and fix a failure here. */
@RunWith(AndroidJUnit4::class)
class ScanPipelineHardwareTest {

    @Test
    fun scansOnePageToAValidJpeg() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = UsbPrinterManager(ctx)
        val device = manager.findPrinter()
            ?: throw AssertionError("No printer found -- is the DeskJet 2300-series MFP connected via USB?")
        val transport = manager.openScanTransport(device)
            ?: throw AssertionError("No LEDM scan interface (255/4) found on the connected device")

        val output = File(ctx.cacheDir, "hardware-scan-test.jpg")
        try {
            ScanPipeline(transport).scan(output)
            assertTrue("Output file should be non-trivial", output.length() > 1024)
            val magic = output.inputStream().use { it.readNBytes(2) }
            assertTrue("Output should start with the JPEG magic bytes", magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte())
        } finally {
            transport.close()
        }
    }
}
```

- [ ] **Step 2: Install and run on the device with a page on the flatbed**

Run: `./gradlew :app:installDebug` (installs the current build), then place a real page
on the DeskJet 2300-series flatbed, then run:
`./gradlew :app:connectedDebugAndroidTest --tests "dev.jaspreet.printserver.ScanPipelineHardwareTest"`

- [ ] **Step 3: If it fails, diagnose against real device behavior — do not guess further**

If the test throws before reaching `PollResult.PageReady` (e.g. an `IOException` from an
unexpected response, or a malformed poll response), capture the actual bytes exchanged for
debugging: temporarily wrap `ScanPipeline`'s `send()` calls with logging (e.g.
`android.util.Log.d("ScanPipeline", "REQUEST: ...")` / `"RESPONSE: ..."`) using
`adb logcat -s ScanPipeline` to see the printer's real request/response text, then compare
against the wire format in `docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md`.
Common things to check first, in order of likelihood:

1. **Host header value**: if the firmware rejects requests entirely (connection closes,
   or a non-200/201 status line), try replacing `"localhost"` with an empty string or
   the literal string the real device's own IEEE-1284 device ID reports (via
   `UsbPrinterManager.readDeviceId`), and re-run.
2. **Region/resolution values**: if scanning succeeds structurally (job created, page
   ready, image fetched) but the JPEG is empty, corrupt, or absurdly cropped, the
   `DEFAULT_WIDTH`/`DEFAULT_HEIGHT` constants in `ScanPipeline.kt` are wrong for this
   device's actual platen. Fix by querying the device's real capabilities once: send
   `GET /Scan/ScanCaps HTTP/1.1` (same request shape as `LedmRequests.statusRequest`,
   just a different path) over the same transport from a scratch/throwaway test or a
   quick REPL-style call, read the XML response, and find the `<Platen>` element's
   `<MaximumSize>` width/height values (in the same units the create-job XML already
   uses) — then hardcode the corrected numbers into `ScanPipeline.kt`'s constants
   (update the comment referencing this task once done) and re-run Step 2.

- [ ] **Step 4: Once passing, commit**

```bash
git add app/src/androidTest/java/dev/jaspreet/printserver/ScanPipelineHardwareTest.kt
# If Step 3 required fixing ScanPipeline.kt's constants or host value, include that file too:
# git add app/src/main/java/dev/jaspreet/printserver/scan/ScanPipeline.kt
git commit -m "test: add device-only hardware smoke test for the scan pipeline"
```

---

### Task 7: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full JVM unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass — including every test added across Tasks 1-5.

- [ ] **Step 2: Build the full debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no native build changes in this plan, but confirms nothing broke)

- [ ] **Step 3: Confirm the hardware test from Task 6 still passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "dev.jaspreet.printserver.ScanPipelineHardwareTest"`
Expected: PASS, with a real physical page on the flatbed.

- [ ] **Step 4: Update `CLAUDE.md` and `AGENTS.md`**

Per this repo's convention (see `CLAUDE.md`'s own instruction to keep both files in
sync), add a short new bullet to the Architecture section describing the scan pipeline,
e.g.: "**Scan pipeline (Spec A)**: `ScanPipeline` drives HP's LEDM scan protocol — real
HTTP/1.1 over a raw USB bulk pipe (interface 255/4), the same shape Tier 1 uses for
IPP-USB printing — to pull a flatbed scan off the DeskJet 2300-series MFP as a JPEG file.
No network-facing scanning yet (no eSCL server, no mDNS `_uscan._tcp` advertisement) —
that's a separate, not-yet-built follow-on (Spec B)." Make the identical edit to both
files.
