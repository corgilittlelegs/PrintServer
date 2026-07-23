package dev.jaspreet.printserver.scan

import dev.jaspreet.printserver.usb.UsbTransport
import java.io.File
import java.io.IOException

/**
 * Drives one flatbed scan over the LEDM scan interface, writing the resulting JPEG
 * bytes to the given output file. See
 * docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md for the protocol
 * source and for why the default region below is a placeholder pending hardware
 * confirmation (Task 6 of this plan).
 *
 * [openTransport] opens a *fresh* USB connection each time it's called, rather than
 * this class holding one connection for the whole scan -- mirrors HPLIP's own
 * bb_start_scan() (scan/sane/bb_ledm.c), which opens/closes its http_handle separately
 * for the status check, the create-job request, and the final binary fetch, reusing one
 * connection only across the poll loop's iterations. Confirmed against real hardware
 * that reusing a single long-lived connection across all of these steps (this class's
 * original design) causes intermittent, hard-to-reproduce failures -- a later request
 * getting back a stale/misframed response from an earlier one on the same connection.
 * Each fresh open also clears both bulk endpoints' halt/data-toggle state
 * (`UsbPrinterManager.openInterface`), matching HPLIP's channel-close behavior.
 */
class ScanPipeline(
    private val openTransport: () -> UsbTransport,
    private val host: String = "localhost",
    private val pollDelayMs: Long = 500,
    private val maxPolls: Int = 60, // ~30s of polling at the default delay
) {
    companion object {
        private const val DEFAULT_RESOLUTION = 300
        private const val DEFAULT_WIDTH = 2550
        private const val DEFAULT_HEIGHT = 3508
        private const val READ_CHUNK = 16384
        private const val JOB_START_SETTLE_MS = 1500L
        private const val START_IDLE_POLLS = 20
    }

    fun scan(
        output: File,
        resolution: Int = DEFAULT_RESOLUTION,
        colorMode: ScanColorMode = ScanColorMode.COLOR,
        brightness: Int = ScanTone.DEFAULT,
        contrast: Int = ScanTone.DEFAULT,
    ) {
        waitUntilIdle()

        val colorSpace = when (colorMode) {
            ScanColorMode.COLOR -> "Color"
            ScanColorMode.GRAYSCALE -> "Gray"
        }
        // HPLIP's bb_ledm.c sends Width/Height in the device's fixed LEDM scan-region
        // coordinate space, independent of XResolution/YResolution. The DeskJet 2300
        // advertises 2550x3508 from /Scan/ScanCaps even when 75dpi is selected; scaling
        // this by DPI makes the carriage scan only a top-left strip (~10-25% of the bed).
        val width = DEFAULT_WIDTH
        val height = DEFAULT_HEIGHT
        val jobBody = LedmRequests.createJobBody(
            resolution, resolution, 0, width, 0, height, colorSpace,
            brightness = ScanTone.resolve(brightness),
            contrast = ScanTone.resolve(contrast),
        )
        val jobBodyBytes = jobBody.toByteArray(Charsets.UTF_8)
        val footerBytes = LedmRequests.ZERO_FOOTER.toByteArray(Charsets.UTF_8)
        val jobHeader = LedmRequests.createJobHeader(host, jobBodyBytes.size + footerBytes.size)
        val jobUrl = withTransport { transport ->
            val reader = send(transport, jobHeader.toByteArray(Charsets.UTF_8), jobBodyBytes, footerBytes)
            val header = ChunkedHttp.readHeader(reader)
            if (!ChunkedHttp.isCreated(header)) {
                ChunkedHttp.readChunkedBody(reader) // body unused, only the Location header matters
            }
            LedmResponses.parseLocationHeader(header)
                ?: throw IOException("No Location header in create-job response")
        }

        // The create-job response confirms the job was accepted, but the flatbed
        // carriage/lamp physically starts moving right around here -- hardware testing
        // showed USB reads failing specifically in this window (electrical noise from
        // motor startup, most likely), never once the scan is already underway. A short
        // pause before hitting the device with more traffic avoids racing that startup
        // transient.
        Thread.sleep(JOB_START_SETTLE_MS)
        val binaryUrl = withTransport { transport -> pollUntilPageReady(transport, jobUrl) }

        withTransport { transport ->
            val reader = send(transport, LedmRequests.getResourceRequest(binaryUrl, host))
            ChunkedHttp.readHeader(reader)
            output.writeBytes(ChunkedHttp.readChunkedBody(reader))
        }
    }

    /** Polls the create-job's Location URL, one open connection for the whole loop,
     *  until the printer reports a page is ready to fetch. */
    private fun pollUntilPageReady(transport: UsbTransport, jobUrl: String): String {
        var pollsLeft = maxPolls
        var isFirstPoll = true
        while (true) {
            val reader = send(transport, LedmRequests.getResourceRequest(jobUrl, host))
            ChunkedHttp.readHeader(reader)
            val body = String(ChunkedHttp.readChunkedBody(reader), Charsets.US_ASCII)
            val result = LedmResponses.parsePollResponse(body, isFirstPoll)
            isFirstPoll = false
            when (result) {
                is PollResult.PageReady -> return result.binaryUrl
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

    private fun waitUntilIdle() {
        var pollsLeft = START_IDLE_POLLS
        while (true) {
            val state = withTransport { transport ->
                val reader = send(transport, LedmRequests.statusRequest(host))
                ChunkedHttp.readHeader(reader)
                val body = String(ChunkedHttp.readChunkedBody(reader), Charsets.US_ASCII)
                LedmResponses.parseScannerState(body)
            }
            if (state == ScannerState.IDLE) return
            if (state != ScannerState.BUSY || --pollsLeft <= 0) {
                throw IOException("Scanner not idle: $state")
            }
            Thread.sleep(pollDelayMs)
        }
    }

    /** Opens a fresh connection via [openTransport], runs [block] against it, and
     *  always closes it afterward -- one USB channel lifecycle per logical request
     *  (or, for the poll loop, per whole loop), matching HPLIP's own connection
     *  lifecycle rather than one connection for the entire scan. */
    private fun <T> withTransport(block: (UsbTransport) -> T): T {
        val transport = openTransport()
        try {
            return block(transport)
        } finally {
            transport.close()
        }
    }

    /** Writes each of [parts] in order, then returns a [PullReader] over the response. */
    private fun send(transport: UsbTransport, vararg parts: ByteArray): PullReader {
        for (part in parts) transport.write(part, 0, part.size)
        return PullReader {
            val buf = ByteArray(READ_CHUNK)
            val n = transport.read(buf)
            buf.copyOf(n)
        }
    }

    private fun send(transport: UsbTransport, request: String): PullReader =
        send(transport, request.toByteArray(Charsets.UTF_8))
}
