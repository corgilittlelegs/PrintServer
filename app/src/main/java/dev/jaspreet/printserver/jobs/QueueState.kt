package dev.jaspreet.printserver.jobs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueueEntry(
    val id: Int,
    val name: String,
    val state: JobState,          // PENDING or PROCESSING only — listActive() never returns others
    val submittedAtMs: Long,
    val sizeBytes: Long,
    val position: Int?,           // 1-based rank among PENDING jobs; null for PROCESSING
)

/**
 * Session-scoped live view of a JobQueue's active (PENDING/PROCESSING) jobs, plus
 * cancel/retry action delegation. Mirrors ActivityLog's singleton-StateFlow pattern —
 * MainActivity isn't bound to ServerService (onBind returns null), so a global object
 * is how UI reaches whichever JobQueue instance is currently running.
 */
object QueueState {
    private val _entries = MutableStateFlow<List<QueueEntry>>(emptyList())
    val entries: StateFlow<List<QueueEntry>> = _entries.asStateFlow()

    @Volatile private var queue: JobQueue? = null

    fun attach(jobQueue: JobQueue) { queue = jobQueue }

    fun detach() {
        queue = null
        _entries.value = emptyList()
    }

    /** Recomputes the visible snapshot from the attached queue's current active jobs.
     *  Racing with detach() is possible (reads `queue` into a local before detach nulls
     *  it) but benign — worst case is one stale snapshot, corrected by the next refresh(). */
    fun refresh() {
        val q = queue ?: return
        _entries.value = q.listActive().sortedBy { it.id }.map { job ->
            QueueEntry(
                id = job.id,
                name = job.name,
                state = job.state,
                submittedAtMs = job.submittedAtMs,
                sizeBytes = job.spoolFile.length(),
                position = q.queuePosition(job.id),
            )
        }
    }

    fun cancel(id: Int): Boolean = queue?.cancel(id) ?: false

    fun retry(id: Int): Int? = queue?.retry(id)
}
