package dev.jaspreet.printserver.jobs

import java.io.File

enum class JobState { PENDING, SPOOLING, PROCESSING, COMPLETED, ABORTED, CANCELED }

class PrintJob(
    val id: Int,
    val name: String,
    val spoolFile: File,
    val format: String = "application/pdf",
    val clientAddress: String? = null,
    val quality: PrintQuality = PrintQuality.NORMAL,
    val colorMode: ColorMode = ColorMode.COLOR,
) {
    @Volatile var state: JobState = JobState.PENDING
    @Volatile var stateReason: String = "none"
    val submittedAtMs: Long = System.currentTimeMillis()
    /** Set when this job was created via [JobQueue.retry] — the original job's id. Tracking only. */
    @Volatile var retryOf: Int? = null
    /**
     * Final size of the fully-spooled document, in bytes. Captured by [JobQueue] at the moment
     * the document is completely written to [spoolFile] (submit-time for Print-Job, enqueue-time
     * for Create-Job/Send-Document) — not re-derived from [spoolFile] later, since the spool file
     * is deleted once a job reaches a terminal state (COMPLETED/CANCELED) or evicted (ABORTED).
     * This lets callers (e.g. the activity feed) report an accurate size even after that deletion.
     */
    @Volatile var spooledBytes: Long = 0L
}
