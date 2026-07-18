package dev.jaspreet.printserver.activity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

enum class ActivityStatus { PRINTING, PRINTED, FAILED }

data class ActivityEntry(
    val id: Int,
    val tier: Int,                     // 1 or 2
    val name: String,                  // Tier 2: client-sent job name; Tier 1: "Print request"
    val status: ActivityStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val clientAddress: String? = null,
    val sizeBytes: Long? = null,
    val format: String? = null,        // Tier 2 only
    val failureReason: String? = null,
    val jobId: Int? = null,            // Tier 2 only — the underlying JobQueue job id, for retry/cancel
)

/**
 * Session-only, in-memory print activity feed. Mirrors the ServerState singleton
 * pattern (service/ServerState.kt) — a plain object with a MutableStateFlow, no DI.
 */
object ActivityLog {
    private const val MAX_ENTRIES = 200

    private val nextId = AtomicInteger(1)
    private val _entries = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()

    /** Creates a new entry (newest-first) and returns its id for later [update] calls. */
    fun record(
        tier: Int,
        name: String,
        status: ActivityStatus,
        startedAt: Long = System.currentTimeMillis(),
        clientAddress: String? = null,
        sizeBytes: Long? = null,
        format: String? = null,
        jobId: Int? = null,
    ): Int {
        val id = nextId.getAndIncrement()
        val entry = ActivityEntry(
            id = id, tier = tier, name = name, status = status, startedAt = startedAt,
            clientAddress = clientAddress, sizeBytes = sizeBytes, format = format, jobId = jobId,
        )
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
        return id
    }

    /** No-op if [id] isn't present (e.g. it already scrolled off the cap). */
    fun update(id: Int, transform: (ActivityEntry) -> ActivityEntry) {
        _entries.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun clear() { _entries.value = emptyList() }
}
