package dev.jaspreet.printserver.scan

import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
}
