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
        // Width/Height are pixel counts at the request's XResolution/YResolution, not a
        // resolution-independent physical size, so the 300dpi-baseline defaults above must
        // be scaled to the actually-requested resolution to keep capturing the full page.
        val width = DEFAULT_WIDTH * resolution / DEFAULT_RESOLUTION
        val height = DEFAULT_HEIGHT * resolution / DEFAULT_RESOLUTION
        val jobBody = LedmRequests.createJobBody(
            resolution, resolution, 0, width, 0, height, colorSpace,
        )
        val jobBodyBytes = jobBody.toByteArray(Charsets.UTF_8)
        val footerBytes = LedmRequests.ZERO_FOOTER.toByteArray(Charsets.UTF_8)
        val jobHeader = LedmRequests.createJobHeader(host, jobBodyBytes.size + footerBytes.size)
        // Sent as one combined write, not three separate ones (header, then body, then
        // footer) -- confirmed against real hardware that splitting this POST across
        // multiple USB bulk transfers made the device never respond at all, unlike the
        // single-part GET requests elsewhere in this class, which work fine as-is.
        val createReader = send(jobHeader.toByteArray(Charsets.UTF_8) + jobBodyBytes + footerBytes)
        val createHeader = ChunkedHttp.readHeader(createReader)
        ChunkedHttp.readChunkedBody(createReader) // body unused, only the Location header matters
        val jobUrl = LedmResponses.parseLocationHeader(createHeader)
            ?: throw IOException("No Location header in create-job response")

        var pollsLeft = maxPolls
        var isFirstPoll = true
        while (true) {
            val pollReader = send(LedmRequests.getResourceRequest(jobUrl, host))
            ChunkedHttp.readHeader(pollReader)
            val pollBody = String(ChunkedHttp.readChunkedBody(pollReader), Charsets.US_ASCII)
            val result = LedmResponses.parsePollResponse(pollBody, isFirstPoll)
            isFirstPoll = false
            when (result) {
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
