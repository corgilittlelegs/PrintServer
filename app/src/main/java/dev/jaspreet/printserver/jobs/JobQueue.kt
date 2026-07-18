package dev.jaspreet.printserver.jobs

import android.util.Log
import dev.jaspreet.printserver.render.RenderingPipeline
import dev.jaspreet.printserver.usb.UsbTransport
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Single-worker FIFO print queue: one physical printer, one USB channel,
 * deliberately no concurrency. Ghostscript/hpcups are not reentrant, so the
 * single worker is also what makes native rendering safe.
 */
class JobQueue(
    private val pipeline: RenderingPipeline,
    private val transportProvider: () -> UsbTransport,
    private val renderTimeoutMs: Long = 120_000,
    /** Called at most once if a render hangs past [renderTimeoutMs]. The native
     *  libraries aren't reentrant and can't be safely interrupted mid-call, so a
     *  hung render leaks its thread and permanently poisons this queue — the
     *  caller (ServerService) is expected to tear down and let the user restart. */
    private val onPipelineStuck: () -> Unit = {},
    /** Fired on every PrintJob state transition (PENDING at submit/reserve, PROCESSING at
     *  render start, terminal state at the end) — for live activity-feed UI. Unlike
     *  [onJobFinished], this also fires for CANCELED and fires multiple times per job. */
    private val onJobStateChanged: (PrintJob) -> Unit = {},
    private val onJobFinished: (PrintJob) -> Unit = {},
) {
    private val nextId = AtomicInteger(1)
    private val jobs = ConcurrentHashMap<Int, PrintJob>()
    private val pending = LinkedBlockingQueue<PrintJob>()
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

    fun submit(spoolFile: File, name: String, format: String = "application/pdf", clientAddress: String? = null): Int {
        val job = PrintJob(nextId.getAndIncrement(), name, spoolFile, format, clientAddress)
        jobs[job.id] = job
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
    fun reserve(spoolFile: File, name: String, format: String = "application/pdf", clientAddress: String? = null): Int {
        val job = PrintJob(nextId.getAndIncrement(), name, spoolFile, format, clientAddress)
        jobs[job.id] = job
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

    /** Hands a job reserved via [reserve] to the worker now that its document is written. */
    fun enqueue(id: Int): Boolean {
        val job = jobs[id] ?: return false
        synchronized(job) {
            if (job.state != JobState.PENDING) return false
            pending.put(job)
            return true
        }
    }

    fun get(id: Int): PrintJob? = jobs[id]

    /** Snapshot of jobs not yet completed/aborted/canceled — for IPP Get-Jobs. */
    fun listActive(): List<PrintJob> = jobs.values.filter {
        it.state == JobState.PENDING || it.state == JobState.PROCESSING
    }

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

    private fun process(job: PrintJob) {
        synchronized(job) {
            if (job.state == JobState.CANCELED) return
            job.state = JobState.PROCESSING
        }
        onJobStateChanged(job)
        val rendered = File(job.spoolFile.parentFile!!, "${job.spoolFile.name}.out")
        try {
            checkFreeSpace(job.spoolFile.parentFile)
            val future = renderExecutor.submit { pipeline.render(job.spoolFile, rendered, job.format) }
            try {
                future.get(renderTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // The render call is still running on renderExecutor's thread and can't be
                // safely killed (native, non-reentrant globals) — poison the queue instead
                // of risking a second render call running concurrently with this one.
                Log.e(TAG, "Job ${job.id} (${job.name}) render exceeded ${renderTimeoutMs}ms — poisoning queue")
                poisoned = true
                job.state = JobState.ABORTED
                job.stateReason = "render-timeout"
                onPipelineStuck()
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
        job.spoolFile.delete()
        onJobStateChanged(job)
        onJobFinished(job)
    }

    /**
     * Streams the rendered file to the printer in fixed chunks instead of
     * loading it whole — multi-page color output at 300dpi can be tens of MB.
     */
    private fun writeToUsb(rendered: File) {
        val transport = transportProvider()
        val buf = ByteArray(65536)
        rendered.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                transport.write(buf, 0, n)
            }
        }
    }

    private fun checkFreeSpace(dir: File) {
        if (dir.usableSpace < MIN_FREE_SPACE_BYTES) {
            throw java.io.IOException("Insufficient cache space to render job (need ${MIN_FREE_SPACE_BYTES / 1_000_000}MB free)")
        }
    }

    fun shutdown() {
        running = false
        worker.interrupt()
        renderExecutor.shutdownNow()
    }

    companion object {
        private const val TAG = "JobQueue"

        // A single 300dpi color A4 page is tens of MB uncompressed; leave headroom for multi-page jobs.
        private const val MIN_FREE_SPACE_BYTES = 200L * 1_000_000L

        // Caps the jobs map (see evictOldTerminalJobs) — mirrors ActivityLog's 200-entry cap.
        private const val MAX_RETAINED_JOBS = 200

        /** Deletes leftover spool/render files from a run that never finished cleanly. Call before construction. */
        fun cleanStaleSpool(dir: File) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
