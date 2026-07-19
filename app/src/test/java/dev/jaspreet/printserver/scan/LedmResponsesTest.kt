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
