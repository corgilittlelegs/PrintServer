package dev.jaspreet.printserver.jobs

import java.io.File

enum class JobState { PENDING, PROCESSING, COMPLETED, ABORTED, CANCELED }

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
}
