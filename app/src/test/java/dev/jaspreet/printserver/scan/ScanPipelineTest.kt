package dev.jaspreet.printserver.scan

import dev.jaspreet.printserver.usb.FakePrinterTransport
import dev.jaspreet.printserver.usb.UsbTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.io.path.createTempFile

class ScanPipelineTest {

    /** ScanPipeline now closes the transport it's given after each logical request
     *  (matching HPLIP's own per-request USB connection lifecycle -- see ScanPipeline's
     *  class doc). These tests script one [FakePrinterTransport] across the whole scan,
     *  so this wraps it to no-op [close] rather than actually closing the shared fake. */
    private class NonClosingTransport(private val delegate: UsbTransport) : UsbTransport {
        override fun write(data: ByteArray, offset: Int, length: Int) = delegate.write(data, offset, length)
        override fun read(buffer: ByteArray): Int = delegate.read(buffer)
        override fun close() {}
    }

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

    private fun createdJobResponse(location: String): ByteArray =
        (
            "HTTP/1.1 201 Created\r\n" +
                "Location: $location\r\n" +
                "Content-Length: 0\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)

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
                    createdJobResponse("/Scan/Jobs/JobList/1")
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
        ScanPipeline({ NonClosingTransport(transport) }, pollDelayMs = 0).scan(output)

        assertArrayEquals(fakeJpeg, output.readBytes())
    }

    @Test
    fun `throws when the scanner reports busy`() {
        val transport = FakePrinterTransport {
            chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>BusyWithScanJob</ScannerState></ScannerStatus>")
        }
        val output = createTempFile().toFile()
        assertThrows(IOException::class.java) { ScanPipeline({ NonClosingTransport(transport) }, pollDelayMs = 0).scan(output) }
    }

    @Test
    fun `waits for the scanner to become idle before creating a new job`() {
        var statusPolls = 0
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
        val transport = FakePrinterTransport { reqBytes ->
            val req = String(reqBytes, Charsets.US_ASCII)
            when {
                req.startsWith("GET /Scan/Status") -> {
                    statusPolls++
                    val state = if (statusPolls == 1) "BusyWithScanJob" else "Idle"
                    chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>$state</ScannerState></ScannerStatus>")
                }
                req.startsWith("POST /Scan/Jobs") ->
                    createdJobResponse("/Scan/Jobs/JobList/1")
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
        ScanPipeline({ NonClosingTransport(transport) }, pollDelayMs = 0).scan(output)

        assertArrayEquals(fakeJpeg, output.readBytes())
        assertTrue("scanner status should have been retried", statusPolls >= 2)
    }

    @Test
    fun `throws when no document is on the flatbed`() {
        val transport = FakePrinterTransport { reqBytes ->
            val req = String(reqBytes, Charsets.US_ASCII)
            when {
                req.startsWith("GET /Scan/Status") ->
                    chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>Idle</ScannerState></ScannerStatus>")
                req.startsWith("POST /Scan/Jobs") ->
                    createdJobResponse("/Scan/Jobs/JobList/1")
                req.startsWith("GET /Scan/Jobs/JobList/1 ") ->
                    chunkedResponse("200 OK", body = "<Jobs><Job><j:JobState>Processing</j:JobState></Job></Jobs>")
                else -> throw IOException("unexpected request: $req")
            }
        }
        val output = createTempFile().toFile()
        assertThrows(IOException::class.java) { ScanPipeline({ NonClosingTransport(transport) }, pollDelayMs = 0).scan(output) }
    }

    @Test
    fun `throws when the job is canceled`() {
        val transport = FakePrinterTransport { reqBytes ->
            val req = String(reqBytes, Charsets.US_ASCII)
            when {
                req.startsWith("GET /Scan/Status") ->
                    chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>Idle</ScannerState></ScannerStatus>")
                req.startsWith("POST /Scan/Jobs") ->
                    createdJobResponse("/Scan/Jobs/JobList/1")
                req.startsWith("GET /Scan/Jobs/JobList/1 ") ->
                    chunkedResponse("200 OK", body = "<PreScanPage><PageState>CanceledByDevice</PageState></PreScanPage>")
                else -> throw IOException("unexpected request: $req")
            }
        }
        val output = createTempFile().toFile()
        assertThrows(IOException::class.java) { ScanPipeline({ NonClosingTransport(transport) }, pollDelayMs = 0).scan(output) }
    }

    @Test
    fun `gives up after the configured number of still-waiting polls`() {
        val transport = FakePrinterTransport { reqBytes ->
            val req = String(reqBytes, Charsets.US_ASCII)
            when {
                req.startsWith("GET /Scan/Status") ->
                    chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>Idle</ScannerState></ScannerStatus>")
                req.startsWith("POST /Scan/Jobs") ->
                    createdJobResponse("/Scan/Jobs/JobList/1")
                req.startsWith("GET /Scan/Jobs/JobList/1 ") ->
                    chunkedResponse("200 OK", body = "<PreScanPage><j:JobState>Processing</j:JobState></PreScanPage>")
                else -> throw IOException("unexpected request: $req")
            }
        }
        val output = createTempFile().toFile()
        assertThrows(IOException::class.java) {
            ScanPipeline({ NonClosingTransport(transport) }, pollDelayMs = 0, maxPolls = 3).scan(output)
        }
    }

    @Test
    fun `passes the requested scan settings into the create-job request`() {
        var capturedJobBody = ""
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
        val transport = FakePrinterTransport { reqBytes ->
            val req = String(reqBytes, Charsets.US_ASCII)
            when {
                req.startsWith("GET /Scan/Status") ->
                    chunkedResponse("200 OK", body = "<ScannerStatus><ScannerState>Idle</ScannerState></ScannerStatus>")
                req.startsWith("POST /Scan/Jobs") -> {
                    capturedJobBody = req.substringAfter("\r\n\r\n")
                    createdJobResponse("/Scan/Jobs/JobList/1")
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
        ScanPipeline({ NonClosingTransport(transport) }, pollDelayMs = 0).scan(
            output,
            resolution = 600,
            colorMode = ScanColorMode.GRAYSCALE,
            brightness = 1200,
            contrast = 800,
        )

        assertTrue(capturedJobBody.contains("<XResolution>600</XResolution>"))
        assertTrue(capturedJobBody.contains("<YResolution>600</YResolution>"))
        assertTrue(capturedJobBody.contains("<ColorSpace>Gray</ColorSpace>"))
        assertTrue(capturedJobBody.contains("<Brightness>1200</Brightness>"))
        assertTrue(capturedJobBody.contains("<Contrast>800</Contrast>"))
        // Width/Height are in the scanner's fixed LEDM region coordinate space, not
        // pixels at the selected output DPI. Scaling these by resolution makes the
        // physical carriage scan only a small top-left strip at low DPI.
        assertTrue(capturedJobBody.contains("<Width>2550</Width>"))
        assertTrue(capturedJobBody.contains("<Height>3508</Height>"))
    }
}
