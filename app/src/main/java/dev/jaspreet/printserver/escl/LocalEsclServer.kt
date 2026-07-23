package dev.jaspreet.printserver.escl

import android.util.Log
import dev.jaspreet.printserver.http.BodyReader
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScanTone
import dev.jaspreet.printserver.scan.ScanToneSettings
import dev.jaspreet.printserver.scan.ScannerCapabilities
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Internal job lifecycle state -- kept as a real enum (unlike the wire-facing
 *  [EsclJobInfo.state] String) so the three states are compiler-checked wherever they're
 *  compared, matching this codebase's [dev.jaspreet.printserver.jobs.JobState] convention. */
private enum class EsclJobState {
    PROCESSING,
    COMPLETED,
    ABORTED,
}

private fun EsclJobState.toWireString(): String = when (this) {
    EsclJobState.PROCESSING -> "Processing"
    EsclJobState.COMPLETED -> "Completed"
    EsclJobState.ABORTED -> "Aborted"
}

private class EsclJob(val id: String, val outputFile: File) {
    @Volatile var state: EsclJobState = EsclJobState.PROCESSING
    val delivered = AtomicBoolean(false)
    val createdAtMs: Long = System.currentTimeMillis()
}

/**
 * A synthetic eSCL scanner: the network-facing sibling of LocalIppServer. Because this
 * hardware supports exactly one scan at a time, [currentJob] is a single slot, not a
 * queue -- a second POST while one is in flight is rejected with 503, matching the real
 * scanner's exclusivity.
 *
 * [performScan] is injected (rather than calling ScanPipeline directly) so JVM tests can
 * substitute a fake scan outcome without real USB hardware -- production wiring (a later
 * task) passes a lambda that calls the real ScanPipeline against a real UsbTransport.
 */
class LocalEsclServer(
    private val port: Int,
    private val makeAndModel: String,
    private val capabilities: ScannerCapabilities,
    private val spoolDir: File,
    private val performScan: (
        resolution: Int,
        colorMode: ScanColorMode,
        brightness: Int,
        contrast: Int,
        output: File,
    ) -> Unit,
    private val defaultToneSettings: () -> ScanToneSettings = { ScanToneSettings() },
    private val maxConcurrentClients: Int = 64,
    private val nextDocumentWaitTimeoutMs: Long = 120_000,
    private val nextDocumentPollDelayMs: Long = 250,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    // Same rationale as LocalIppServer: bound thread growth from concurrent
    // connections so a flood of LAN connections can't OOM the app.
    private val clientSlots = Semaphore(maxConcurrentClients)
    private val currentJob = AtomicReference<EsclJob?>(null)
    private val nextJobId = AtomicInteger(1)

    // currentJob is cleared (set back to null) as soon as a scan finishes, whether it
    // succeeded or failed -- that's what lets a new POST start immediately after. But
    // NextDocument/DELETE requests for that job typically arrive *after* it's cleared (the
    // client polls ScannerStatus until it's no longer Processing, then fetches), so its
    // terminal state and output file must stay reachable independent of currentJob. This
    // map is the source of truth for both; entries are removed on DELETE, which every real
    // eSCL client issues after a successful fetch. A non-compliant or crashed client that
    // never sends DELETE would otherwise grow this map forever -- evictOldTerminalJobs()
    // bounds it at MAX_RETAINED_JOBS, matching JobQueue's convention.
    private val jobs = ConcurrentHashMap<String, EsclJob>()

    val actualPort: Int get() = serverSocket?.localPort ?: port

    fun start(bindAddress: InetAddress?) {
        val ss = ServerSocket(port, 50, bindAddress)
        serverSocket = ss
        executor.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                if (!clientSlots.tryAcquire()) {
                    try { client.close() } catch (_: IOException) {}
                    continue
                }
                executor.execute {
                    try { handleClient(client) } finally { clientSlots.release() }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 30_000
        client.use {
            val cin = BufferedInputStream(client.getInputStream())
            val cout = BufferedOutputStream(client.getOutputStream())
            val head = try { HttpHead.parse(cin) ?: return } catch (_: IOException) { return }
            val (method, path) = parseStartLine(head.startLine) ?: run {
                respond(cout, 400, "text/plain", "Bad Request")
                return
            }
            val body = try { BodyReader.readAll(head, cin) } catch (_: IOException) { ByteArray(0) }

            when {
                method == "GET" && path == "/eSCL/ScannerCapabilities" -> {
                    logRequest(method, path, 200)
                    respond(cout, 200, "text/xml", EsclXml.scannerCapabilities(capabilities, makeAndModel))
                }
                method == "GET" && path == "/eSCL/ScannerStatus" -> {
                    val jobs = currentJobInfo()
                    logRequest(method, path, 200, "jobs=${jobs.joinToString { "${it.id}:${it.state}" }}")
                    respond(cout, 200, "text/xml", EsclXml.scannerStatus(jobs))
                }
                method == "POST" && path == "/eSCL/ScanJobs" ->
                    handleCreateJob(cout, body)
                method == "GET" && path.startsWith("/eSCL/ScanJobs/") && path.endsWith("/NextDocument") ->
                    handleNextDocument(cout, path)
                method == "DELETE" && path.startsWith("/eSCL/ScanJobs/") ->
                    handleDeleteJob(cout, path)
                else -> respond(cout, 404, "text/plain", "Not Found")
            }
        }
    }

    private fun handleCreateJob(cout: BufferedOutputStream, body: ByteArray) {
        val settings = EsclXml.parseScanSettings(String(body, Charsets.UTF_8))
        val resolution = settings.resolution?.takeIf { it in capabilities.supportedResolutions }
            ?: capabilities.supportedResolutions.firstOrNull() ?: 300
        val colorMode = settings.colorMode?.takeIf { it in capabilities.supportedColorModes }
            ?: capabilities.supportedColorModes.firstOrNull() ?: ScanColorMode.COLOR
        val defaults = defaultToneSettings()
        val brightness = ScanTone.resolve(settings.brightness, defaults.brightness)
        val contrast = ScanTone.resolve(settings.contrast, defaults.contrast)

        spoolDir.mkdirs()
        val id = nextJobId.getAndIncrement().toString()
        val output = File(spoolDir, "escl-job-$id.jpg")
        val job = EsclJob(id, output)
        if (!currentJob.compareAndSet(null, job)) {
            logRequest("POST", "/eSCL/ScanJobs", 503, "scanner busy")
            respond(cout, 503, "text/plain", "Scanner busy")
            return
        }
        jobs[id] = job
        executor.execute {
            try {
                performScan(resolution, colorMode, brightness, contrast, output)
                job.state = EsclJobState.COMPLETED
                Log.i("LocalEsclServer", "eSCL job ${job.id} completed outputBytes=${output.length()}")
            } catch (e: Exception) {
                Log.w("LocalEsclServer", "Scan job ${job.id} aborted: ${e.message}", e)
                job.state = EsclJobState.ABORTED
            } finally {
                // Finish all bookkeeping (eviction / orphan cleanup) *before* clearing
                // currentJob, since that's the signal that lets a new POST start --
                // otherwise a fresh scan could begin while this job's cleanup is still
                // in flight.
                if (jobs.containsKey(job.id)) {
                    evictOldTerminalJobs()
                } else {
                    // DELETE already ran for this job while the scan was still
                    // PROCESSING -- it removed the job from `jobs` and deleted
                    // whatever (if anything) existed on disk at that moment. The
                    // performScan call above may have just written bytes to `output`
                    // *after* that, which would otherwise resurrect an orphaned,
                    // untracked file. Delete it again rather than leaving it behind.
                    output.delete()
                }
                currentJob.compareAndSet(job, null)
            }
        }
        logRequest(
            "POST",
            "/eSCL/ScanJobs",
            201,
            "job=$id resolution=$resolution colorMode=$colorMode brightness=$brightness contrast=$contrast",
        )
        respondWithHeaders(cout, 201, "text/plain", "", mapOf("Location" to "/eSCL/ScanJobs/$id"))
    }

    /**
     * Bounds the jobs map the same way JobQueue.evictOldTerminalJobs does: a client that
     * never sends DELETE (crash, dropped Wi-Fi, non-compliant client) would otherwise grow
     * this map -- and its spooled output files -- forever over a long-running session. Only
     * terminal (COMPLETED/ABORTED) jobs are evicted, oldest first, matching the codebase's
     * 200-retained DoS-hardening convention (see JobQueue, ActivityLog).
     */
    private fun evictOldTerminalJobs() {
        val overflow = jobs.size - MAX_RETAINED_JOBS
        if (overflow <= 0) return
        jobs.values
            .filter { it.state == EsclJobState.COMPLETED || it.state == EsclJobState.ABORTED }
            .sortedBy { it.id.toInt() }
            .take(overflow)
            .forEach {
                it.outputFile.delete()
                jobs.remove(it.id)
            }
    }

    private fun handleNextDocument(cout: BufferedOutputStream, path: String) {
        val id = path.removePrefix("/eSCL/ScanJobs/").removeSuffix("/NextDocument")
        val job = waitForNextDocumentJob(id)
        // A known job that isn't COMPLETED must never be served -- Processing means no
        // bytes are ready yet, and Aborted means whatever bytes made it to disk may be a
        // partial/corrupt write from a failure mid-scan (e.g. a USB write error). Gating
        // positively on COMPLETED (rather than just excluding Processing) is what keeps an
        // Aborted job's partial output from ever going out as a client-visible 200.
        if (job == null) {
            logRequest("GET", path, 404, "job=$id missing")
            respond(cout, 404, "text/plain", "Not ready")
            return
        }
        if (job.state != EsclJobState.COMPLETED) {
            val status = if (job.state == EsclJobState.ABORTED) 500 else 404
            logRequest("GET", path, status, "job=$id state=${job.state}")
            respond(cout, status, "text/plain", "Not ready")
            return
        }
        if (!job.delivered.compareAndSet(false, true)) {
            logRequest("GET", path, 404, "job=$id already delivered")
            respond(cout, 404, "text/plain", "No more documents")
            return
        }
        if (!job.outputFile.exists()) {
            logRequest("GET", path, 404, "job=$id output missing")
            respond(cout, 404, "text/plain", "Not ready")
            return
        }
        val bodyBytes = job.outputFile.readBytes()
        try {
            logRequest("GET", path, 200, "job=$id bytes=${bodyBytes.size}")
            respondWithHeaders(cout, 200, "image/jpeg", "", emptyMap(), bodyBytes = bodyBytes)
        } finally {
            jobs.remove(id, job)
            job.outputFile.delete()
        }
    }

    /**
     * Some Windows eSCL clients request NextDocument immediately after POSTing a scan job,
     * then poll ScannerStatus/NextDocument in a tight loop while the hardware is still
     * scanning. Returning 404 for those early reads can make the client conclude that
     * there is no image to display; it may DELETE the job as soon as status flips to
     * Completed without ever issuing a successful image fetch.
     *
     * Treat NextDocument as a bounded long-poll instead: if the job is still Processing,
     * hold this request until it becomes terminal (or until the wait budget expires). That
     * lets the same client request receive the JPEG as soon as the scanner finishes.
     */
    private fun waitForNextDocumentJob(id: String): EsclJob? {
        val deadline = System.currentTimeMillis() + nextDocumentWaitTimeoutMs.coerceAtLeast(0)
        while (true) {
            val job = jobs[id] ?: return null
            if (job.state != EsclJobState.PROCESSING) return job
            if (System.currentTimeMillis() >= deadline) return job
            try {
                Thread.sleep(nextDocumentPollDelayMs.coerceAtLeast(1))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return job
            }
        }
    }

    private fun handleDeleteJob(cout: BufferedOutputStream, path: String) {
        val id = path.removePrefix("/eSCL/ScanJobs/")
        val job = currentJob.get()
        // Only release the slot here if the job is already terminal. While it's still
        // PROCESSING, performScan is actively running on a background thread against the
        // single shared UsbTransport -- clearing currentJob now would let a subsequent POST
        // start a second concurrent performScan call, interleaving USB bulk pipe HTTP
        // framing between the two scans. The async completion block in handleCreateJob's
        // finally is the sole rightful owner of releasing the slot for a job that's still
        // processing; DELETE just removes the map entry / spooled file below.
        if (job?.id == id && job.state != EsclJobState.PROCESSING) currentJob.compareAndSet(job, null)
        // The job may already have left currentJob (a completed job clears its own slot
        // in handleCreateJob's executor block) by the time DELETE arrives, so look up its
        // output file via the jobs map -- which, unlike currentJob, isn't cleared on
        // completion -- rather than relying on a still-live EsclJob reference. Without
        // this, every completed scan's spooled JPEG is left on disk forever -- see
        // jobs/JobQueue.kt for the established convention of deleting spool files once a
        // job is terminal.
        val trackedJob = jobs.remove(id)
        val output = trackedJob?.outputFile ?: File(spoolDir, "escl-job-$id.jpg")
        if (output.exists()) output.delete()
        logRequest("DELETE", path, 200, "job=$id tracked=${trackedJob != null} state=${trackedJob?.state}")
        respond(cout, 200, "text/plain", "")
    }

    private fun currentJobInfo(): List<EsclJobInfo> {
        val trackedJobs = jobs.values.associateBy { it.id }.toMutableMap()
        currentJob.get()?.let { trackedJobs.putIfAbsent(it.id, it) }
        return trackedJobs.values
            .sortedBy { it.id.toInt() }
            .map { job ->
                EsclJobInfo(
                    id = job.id,
                    state = job.state.toWireString(),
                    ageSeconds = ((System.currentTimeMillis() - job.createdAtMs) / 1000).coerceAtLeast(0),
                    imagesCompleted = if (job.state == EsclJobState.COMPLETED) 1 else 0,
                    imagesToTransfer = if (job.state == EsclJobState.COMPLETED && !job.delivered.get()) 1 else 0,
                    stateReason = when (job.state) {
                        EsclJobState.PROCESSING -> "JobScanning"
                        EsclJobState.COMPLETED -> "JobCompletedSuccessfully"
                        EsclJobState.ABORTED -> "ImageTransferError"
                    },
                )
            }
    }

    private fun parseStartLine(startLine: String): Pair<String, String>? {
        val parts = startLine.split(" ")
        if (parts.size < 2) return null
        return parts[0] to parts[1]
    }

    private fun respond(cout: BufferedOutputStream, status: Int, contentType: String, body: String) =
        respondWithHeaders(cout, status, contentType, body, emptyMap())

    private fun logRequest(method: String, path: String, status: Int, detail: String = "") {
        val suffix = if (detail.isBlank()) "" else " $detail"
        Log.i("LocalEsclServer", "eSCL $method $path -> $status$suffix")
    }

    private fun respondWithHeaders(
        cout: BufferedOutputStream,
        status: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String>,
        bodyBytes: ByteArray = body.toByteArray(Charsets.UTF_8),
    ) {
        val statusText = when (status) {
            200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"
            404 -> "Not Found"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Connection: close\r\n")
            append("\r\n")
        }
        cout.write(headers.toByteArray(Charsets.ISO_8859_1))
        cout.write(bodyBytes)
        cout.flush()
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }

    companion object {
        // Caps the jobs map (see evictOldTerminalJobs) -- mirrors JobQueue's/ActivityLog's 200-entry cap.
        private const val MAX_RETAINED_JOBS = 200
    }
}
