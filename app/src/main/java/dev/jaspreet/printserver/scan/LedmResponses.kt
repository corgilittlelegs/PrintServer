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
     *  this order, to each individual poll response in turn (not accumulated state).
     *
     *  [isFirstPoll] distinguishes "no paper was ever detected" from "the job just
     *  hasn't produced a page description yet": absence of `<PreScanPage>` only means
     *  no-document on the very first poll. On later polls we already know paper was on
     *  the flatbed (that's how we got a job in the first place), so treat it as
     *  still-waiting instead of bailing. */
    fun parsePollResponse(body: String, isFirstPoll: Boolean): PollResult = when {
        !body.contains("<PreScanPage>") -> if (isFirstPoll) PollResult.NoDocument else PollResult.StillWaiting
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
