package dev.jaspreet.printserver.jobs

import dev.jaspreet.printserver.render.RenderingPipeline
import dev.jaspreet.printserver.usb.UsbTransport
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
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
    private val onJobFinished: (PrintJob) -> Unit = {},
) {
    private val nextId = AtomicInteger(1)
    private val jobs = ConcurrentHashMap<Int, PrintJob>()
    private val pending = LinkedBlockingQueue<PrintJob>()
    @Volatile private var running = true

    private val worker = thread(name = "print-worker") {
        while (running) {
            val job = try { pending.take() } catch (_: InterruptedException) { break }
            if (job.state == JobState.CANCELED) continue
            process(job)
        }
    }

    fun submit(spoolFile: File, name: String): Int {
        val job = PrintJob(nextId.getAndIncrement(), name, spoolFile)
        jobs[job.id] = job
        pending.put(job)
        return job.id
    }

    fun get(id: Int): PrintJob? = jobs[id]

    /** True if the job was still pending and is now canceled. */
    fun cancel(id: Int): Boolean {
        val job = jobs[id] ?: return false
        synchronized(job) {
            if (job.state != JobState.PENDING) return false
            job.state = JobState.CANCELED
            job.spoolFile.delete()
            return true
        }
    }

    private fun process(job: PrintJob) {
        synchronized(job) {
            if (job.state == JobState.CANCELED) return
            job.state = JobState.PROCESSING
        }
        val rendered = File(job.spoolFile.parentFile!!, "${job.spoolFile.name}.out")
        try {
            checkFreeSpace(job.spoolFile.parentFile)
            pipeline.render(job.spoolFile, rendered)
            writeToUsb(rendered)
            job.state = JobState.COMPLETED
        } catch (e: Exception) {
            job.state = JobState.ABORTED
            job.stateReason = "document-format-error"
        } finally {
            job.spoolFile.delete()
            rendered.delete()
            onJobFinished(job)
        }
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
    }

    companion object {
        // A single 300dpi color A4 page is tens of MB uncompressed; leave headroom for multi-page jobs.
        private const val MIN_FREE_SPACE_BYTES = 200L * 1_000_000L

        /** Deletes leftover spool/render files from a run that never finished cleanly. Call before construction. */
        fun cleanStaleSpool(dir: File) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
