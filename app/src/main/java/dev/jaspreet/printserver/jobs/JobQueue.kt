package dev.jaspreet.printserver.jobs

import android.util.Log
import dev.jaspreet.printserver.render.RecoverableRenderingPipeline
import dev.jaspreet.printserver.render.RenderingPipeline
import dev.jaspreet.printserver.usb.LegacyPrinterSession
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class JobQueueCapacityException(message: String) : IOException(message)

/**
 * Single-worker FIFO print queue: one physical printer, one USB channel,
 * deliberately no concurrency. Ghostscript/hpcups are not reentrant, so the
 * single worker is also what makes native rendering safe.
 */
class JobQueue(
    private val pipeline: RenderingPipeline,
    /** Shared with Raw9100Relay so both writers to the legacy USB transport are mutually
     *  exclusive — see [LegacyPrinterSession] for why unguarded interleaved writes corrupt
     *  the printer's byte stream. */
    private val legacySession: LegacyPrinterSession,
    private val renderTimeoutMs: Long = 120_000,
    /** Called at most once if a render hangs past [renderTimeoutMs] and the pipeline cannot
     *  prove that its native renderer was terminated. Recoverable out-of-process renderers
     *  keep the queue usable after killing their disposable worker process. */
    private val onPipelineStuck: () -> Unit = {},
    private val maxActiveJobs: Int = 16,
    private val maxReservedJobsPerClient: Int = 4,
    private val reservationTtlMs: Long = 5 * 60_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /** Fired on every PrintJob state transition (PENDING at submit/reserve, PROCESSING at
     *  render start, terminal state at the end) — for live activity-feed UI. Unlike
     *  [onJobFinished], this also fires for CANCELED and fires multiple times per job. */
    private val onJobStateChanged: (PrintJob) -> Unit = {},
    private val onJobFinished: (PrintJob) -> Unit = {},
) {
    private val nextId = AtomicInteger(1)
    private val jobs = ConcurrentHashMap<Int, PrintJob>()
    private val pending = LinkedBlockingQueue<PrintJob>()
    private val admissionLock = Any()
    @Volatile private var running = true
    @Volatile private var poisoned = false

    // Dedicated so a timed-out render's thread (which we cannot safely interrupt or
    // reuse — see onPipelineStuck) is isolated from the worker loop below.
    private val renderExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val worker = thread(name = "print-worker") {
        while (running) {
            val job = try { pending.take() } catch (_: InterruptedException) { break }
            if (job.state == JobState.CANCELED) continue
            if (poisoned) {
                failWithoutRendering(job, "queue-unavailable")
                continue
            }
            process(job)
        }
    }

    fun submit(
        spoolFile: File,
        name: String,
        format: String = "application/pdf",
        clientAddress: String? = null,
        quality: PrintQuality = PrintQuality.NORMAL,
        colorMode: ColorMode = ColorMode.COLOR,
    ): Int {
        expireReservations()
        val job = synchronized(admissionLock) {
            ensureActiveCapacity()
            PrintJob(nextId.getAndIncrement(), name, spoolFile, format, clientAddress, quality, colorMode)
                .also { jobs[it.id] = it }
        }
        // Print-Job's document is already fully streamed to spoolFile by the caller before
        // submit() is invoked, so this is the final size — capture it now, durably, since
        // spoolFile itself gets deleted once the job reaches a terminal state.
        job.spooledBytes = spoolFile.length()
        pending.put(job)
        onJobStateChanged(job)
        evictOldTerminalJobs()
        return job.id
    }

    /**
     * Registers a job that isn't ready to run yet — for the IPP Create-Job / Send-Document
     * two-phase flow, where the client reserves a job-id before the document bytes arrive
     * in a later request. [spoolFile] must exist (even if empty); [enqueue] hands the job
     * to the worker once its document has actually been written.
     */
    fun reserve(
        spoolFile: File,
        name: String,
        format: String = "application/pdf",
        clientAddress: String? = null,
        quality: PrintQuality = PrintQuality.NORMAL,
        colorMode: ColorMode = ColorMode.COLOR,
    ): Int {
        expireReservations()
        val job = synchronized(admissionLock) {
            ensureActiveCapacity()
            val reservationsForClient = jobs.values.count {
                it.reservationExpiresAtMs > 0L && sameOwner(it.clientAddress, clientAddress)
            }
            if (reservationsForClient >= maxReservedJobsPerClient) {
                throw JobQueueCapacityException("Too many unfilled job reservations for this client")
            }
            PrintJob(nextId.getAndIncrement(), name, spoolFile, format, clientAddress, quality, colorMode).also {
                it.reservationExpiresAtMs = clockMs() + reservationTtlMs
                jobs[it.id] = it
            }
        }
        onJobStateChanged(job)
        evictOldTerminalJobs()
        return job.id
    }

    /**
     * Bounds the jobs map: a client repeatedly submitting jobs over a long-running
     * session would otherwise grow this map forever (terminal jobs are never
     * otherwise removed). Only completed/aborted/canceled jobs are evicted, oldest
     * first, so active (pending/processing) jobs are never dropped.
     */
    private fun evictOldTerminalJobs() {
        val overflow = jobs.size - MAX_RETAINED_JOBS
        if (overflow <= 0) return
        jobs.values
            .filter { it.state == JobState.COMPLETED || it.state == JobState.ABORTED || it.state == JobState.CANCELED }
            .sortedBy { it.id }
            .take(overflow)
            .forEach {
                it.spoolFile.delete()
                jobs.remove(it.id)
            }
    }

    /**
     * Atomically claims a reserved Create-Job for the single Send-Document request that is
     * allowed to write its bytes. This closes the replay/race window where two clients could
     * both observe PENDING, append to the same spool file, then enqueue the same job twice.
     */
    fun beginSpooling(id: Int): PrintJob? {
        expireReservations()
        val job = jobs[id] ?: return null
        synchronized(job) {
            if (job.state != JobState.PENDING) return null
            job.state = JobState.SPOOLING
            job.stateReason = "job-incoming"
            job.reservationExpiresAtMs = 0L
            return job
        }
    }

    /** Same as [beginSpooling], but prevents another LAN client from claiming a guessed job id. */
    fun beginSpoolingOwned(id: Int, clientAddress: String?): PrintJob? {
        val job = jobs[id] ?: return null
        if (!sameOwner(job.clientAddress, clientAddress)) return null
        return beginSpooling(id)
    }

    /** Hands a job reserved via [reserve] to the worker now that its document is written. */
    fun enqueue(id: Int): Boolean {
        val job = jobs[id] ?: return false
        synchronized(job) {
            if (job.state != JobState.SPOOLING) return false
            // The caller (LocalIppServer.sendDocument) has just finished streaming the
            // document into job.spoolFile — capture the final size now, durably, while the
            // file is guaranteed to still exist and reflect the complete document.
            job.spooledBytes = job.spoolFile.length()
            job.state = JobState.PENDING
            job.stateReason = "none"
            pending.put(job)
            return true
        }
    }

    fun get(id: Int): PrintJob? {
        expireReservations()
        return jobs[id]
    }

    fun getOwned(id: Int, clientAddress: String?): PrintJob? =
        get(id)?.takeIf { sameOwner(it.clientAddress, clientAddress) }

    /** Snapshot of jobs not yet completed/aborted/canceled — for IPP Get-Jobs. */
    fun listActive(): List<PrintJob> {
        expireReservations()
        return jobs.values.filter {
        it.state == JobState.PENDING || it.state == JobState.SPOOLING || it.state == JobState.PROCESSING
        }
    }

    fun listActiveForClient(clientAddress: String?): List<PrintJob> =
        listActive().filter { sameOwner(it.clientAddress, clientAddress) }

    /** True if the job was still pending and is now canceled. */
    fun cancel(id: Int): Boolean {
        val job = jobs[id] ?: return false
        val canceled = synchronized(job) {
            if (job.state != JobState.PENDING) return false
            job.state = JobState.CANCELED
            true
        }
        if (canceled) {
            onJobStateChanged(job)
            job.spoolFile.delete()
        }
        return canceled
    }

    fun cancelOwned(id: Int, clientAddress: String?): Boolean {
        val job = getOwned(id, clientAddress) ?: return false
        return cancel(job.id)
    }

    /** Expires abandoned Create-Job reservations and releases their active-job slots. */
    fun expireReservations() {
        val now = clockMs()
        val expired = jobs.values.filter { job ->
            synchronized(job) {
                if (job.state != JobState.PENDING || job.reservationExpiresAtMs <= 0L ||
                    job.reservationExpiresAtMs > now
                ) {
                    false
                } else {
                    job.state = JobState.ABORTED
                    job.stateReason = "job-reservation-expired"
                    job.reservationExpiresAtMs = 0L
                    true
                }
            }
        }
        expired.forEach {
            it.spoolFile.delete()
            onJobStateChanged(it)
            onJobFinished(it)
        }
    }

    private fun ensureActiveCapacity() {
        val active = jobs.values.count {
            it.state == JobState.PENDING || it.state == JobState.SPOOLING || it.state == JobState.PROCESSING
        }
        if (active >= maxActiveJobs) throw JobQueueCapacityException("Print queue is full")
    }

    private fun sameOwner(first: String?, second: String?): Boolean = first == second

    /**
     * Finalizes a still-PENDING/SPOOLING job as ABORTED with [reason] without ever handing it to the
     * worker — for a two-phase (Create-Job/Send-Document) job whose document delivery fails
     * before it's enqueued (e.g. Send-Document's body exceeds the size cap). Mirrors [cancel]'s
     * shape, but sets [PrintJob.stateReason] and fires [onJobFinished] too, since this is a
     * genuine terminal failure the client/activity feed needs to see — not a cancellation.
     *
     * The caller is responsible for the spool file's contents (e.g. [LocalIppServer]'s
     * streamToFile already truncates a failed append back to its pre-call length); this only
     * updates job state. Returns false if [id] is unknown or the job already left PENDING/SPOOLING.
     */
    fun fail(id: Int, reason: String): Boolean {
        val job = jobs[id] ?: return false
        val failed = synchronized(job) {
            if (job.state != JobState.PENDING && job.state != JobState.SPOOLING) return false
            job.state = JobState.ABORTED
            job.stateReason = reason
            true
        }
        if (failed) {
            onJobStateChanged(job)
            onJobFinished(job)
        }
        return failed
    }

    /**
     * Resubmits an ABORTED job's original document as a new job, copied into a fresh
     * spool file (never shares the original file with the new job — a second retry of
     * the same original id, or the new job completing and deleting its own file, must
     * not affect the other). Returns the new job's id, or null if [id] isn't an
     * eligible ABORTED job with its spool file still present (e.g. already evicted).
     */
    fun retry(id: Int): Int? {
        val job = jobs[id] ?: return null
        if (job.state != JobState.ABORTED) return null
        if (!job.spoolFile.exists()) return null
        val copy = File.createTempFile("retry", ".${job.spoolFile.extension}", job.spoolFile.parentFile)
        job.spoolFile.copyTo(copy, overwrite = true)
        val newId = submit(copy, job.name, job.format, job.clientAddress, job.quality, job.colorMode)
        jobs[newId]?.retryOf = id
        return newId
    }

    /**
     * 1-based rank of [id] among currently-PENDING jobs, oldest first — nextId is a
     * strictly increasing AtomicInteger, so id order already equals FIFO submission
     * order. Returns null if [id] is unknown, PROCESSING, or already terminal.
     */
    fun queuePosition(id: Int): Int? {
        val job = jobs[id] ?: return null
        if (job.state != JobState.PENDING) return null
        return jobs.values.count { it.state == JobState.PENDING && it.id < id } + 1
    }

    private fun process(job: PrintJob) {
        synchronized(job) {
            if (job.state == JobState.CANCELED) return
            job.state = JobState.PROCESSING
        }
        onJobStateChanged(job)
        val spoolParent = job.spoolFile.parentFile ?: throw IOException("Spool file has no parent directory")
        val rendered = File(spoolParent, "${job.spoolFile.name}.out")
        try {
            checkFreeSpace(spoolParent)
            val future = renderExecutor.submit { pipeline.render(job.spoolFile, rendered, job.format, job.quality, job.colorMode) }
            try {
                future.get(renderTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // Kill the disposable renderer before interrupting the Binder caller. The
                // caller's finally block clears its active PID, so reversing this order can
                // lose the only verified process identity before recovery gets to use it.
                val recovered = try {
                    (pipeline as? RecoverableRenderingPipeline)?.recoverFromTimeout() == true
                } catch (recoveryError: Exception) {
                    Log.e(TAG, "Renderer timeout recovery failed", recoveryError)
                    false
                }
                future.cancel(true)
                job.state = JobState.ABORTED
                job.stateReason = "render-timeout"
                if (recovered) {
                    Log.w(TAG, "Job ${job.id} (${job.name}) render timed out; renderer process terminated")
                } else {
                    // Never start another native call unless termination was proven.
                    Log.e(TAG, "Job ${job.id} (${job.name}) render timed out; poisoning queue")
                    poisoned = true
                    onPipelineStuck()
                }
                return
            }
            writeToUsb(rendered)
            job.state = JobState.COMPLETED
        } catch (e: Exception) {
            Log.e(TAG, "Job ${job.id} (${job.name}) failed", e)
            job.state = JobState.ABORTED
            job.stateReason = "document-format-error"
        } finally {
            // ABORTED jobs keep their spool file so JobQueue.retry() can resubmit it;
            // it's cleaned up by evictOldTerminalJobs() or cleanStaleSpool() on restart.
            if (job.state != JobState.ABORTED) job.spoolFile.delete()
            rendered.delete()
            onJobStateChanged(job)
            onJobFinished(job)
        }
    }

    private fun failWithoutRendering(job: PrintJob, reason: String) {
        synchronized(job) {
            if (job.state == JobState.CANCELED) return
            job.state = JobState.ABORTED
        }
        job.stateReason = reason
        if (job.state != JobState.ABORTED) job.spoolFile.delete()
        onJobStateChanged(job)
        onJobFinished(job)
    }

    /**
     * Streams the rendered file to the printer in fixed chunks instead of
     * loading it whole — multi-page color output at 300dpi can be tens of MB.
     */
    private fun writeToUsb(rendered: File) {
        // Held for the whole multi-chunk write, not re-acquired per chunk — a raw-9100
        // client's write must never land between two chunks of the same job.
        legacySession.writeExclusive("print-job") { transport ->
            val buf = ByteArray(65536)
            rendered.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    transport.write(buf, 0, n)
                }
            }
        }
    }

    private fun checkFreeSpace(dir: File) {
        if (dir.usableSpace < MIN_FREE_SPACE_BYTES) {
            throw java.io.IOException("Insufficient cache space to render job (need ${MIN_FREE_SPACE_BYTES / 1_000_000}MB free)")
        }
    }

    /**
     * Stops the worker and waits (bounded by [joinTimeoutMs]) for it to actually exit before
     * returning. [writeExclusive] deliberately doesn't respond to interrupt (see
     * [LegacyPrinterSession]), so an in-flight write survives [worker]'s interrupt() and keeps
     * running on the shared legacy USB transport — callers that close that transport right
     * after shutdown() (e.g. [dev.jaspreet.printserver.service.ServerService.stopPipeline])
     * must not race that write, which this join makes safe in the common case.
     */
    fun shutdown(joinTimeoutMs: Long = SHUTDOWN_JOIN_TIMEOUT_MS) {
        running = false
        worker.interrupt()
        renderExecutor.shutdownNow()
        worker.join(joinTimeoutMs)
        (pipeline as? AutoCloseable)?.let {
            try { it.close() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "JobQueue"

        // A single 300dpi color A4 page is tens of MB uncompressed; leave headroom for multi-page jobs.
        private const val MIN_FREE_SPACE_BYTES = 200L * 1_000_000L

        // Caps the jobs map (see evictOldTerminalJobs) — mirrors ActivityLog's 200-entry cap.
        private const val MAX_RETAINED_JOBS = 200

        // Bounds how long shutdown() waits for an in-flight write to finish — long enough for
        // a large multi-page job's final USB chunks, short enough not to hang app teardown.
        private const val SHUTDOWN_JOIN_TIMEOUT_MS = 10_000L

        /** Deletes leftover spool/render files from a run that never finished cleanly. Call before construction. */
        fun cleanStaleSpool(dir: File) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
