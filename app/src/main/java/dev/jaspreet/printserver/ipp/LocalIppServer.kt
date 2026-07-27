package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.JobState as IppJobState
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import dev.jaspreet.printserver.http.BodyReader
import dev.jaspreet.printserver.http.BodyTooLargeException
import dev.jaspreet.printserver.http.DecodedBodyInputStream
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.jobs.JobState
import dev.jaspreet.printserver.jobs.PrintQuality
import dev.jaspreet.printserver.scan.SupplyStatus
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * A synthetic IPP printer: the app IS the printer as far as clients can tell.
 * Tier-2 counterpart of IppRelayServer (there is no printer-side IPP to relay).
 */
class LocalIppServer(
    private val port: Int,
    private val capabilities: PrinterCapabilities,
    private val jobQueue: JobQueue,
    private val spoolDir: File,
    private val maxDocumentBytes: Long = BodyReader.DEFAULT_MAX_BYTES,
    private val maxConcurrentClients: Int = 64,
    private val supplyStatusProvider: () -> SupplyStatus? = { null },
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    // Same rationale as IppRelayServer: bound thread growth from concurrent
    // connections so a flood of LAN connections can't OOM the app.
    private val clientSlots = Semaphore(maxConcurrentClients)

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
        val clientAddress = client.inetAddress?.hostAddress
        client.use {
            val cin = BufferedInputStream(client.getInputStream())
            val cout = BufferedOutputStream(client.getOutputStream())
            while (true) {
                val head = try { HttpHead.parse(cin) ?: break } catch (_: IOException) { break }
                val response = try {
                    // DecodedBodyInputStream presents exactly this request's body (whatever the
                    // framing) as a stream that stops at -1 at its logical end, including
                    // consuming chunked framing/trailers — so handleIpp can parse the (small,
                    // bounded) IPP attribute packet directly off it, then stream any remaining
                    // document bytes straight to a spool file, without ever buffering the whole
                    // body in memory. Draining it fully (handleIpp guarantees this) is what keeps
                    // cin correctly positioned for the next pipelined request below.
                    val decodedBody = DecodedBodyInputStream(head, cin, maxDocumentBytes)
                    handleIpp(decodedBody, clientAddress)
                } catch (e: BodyTooLargeException) {
                    // The client already sent bytes we're discarding; the connection
                    // can't be reused, so this is the last response on it (Step below).
                    errorResponse(0, Status.clientErrorRequestEntityTooLarge)
                } catch (e: IOException) {
                    break
                } catch (e: Exception) {
                    errorResponse(0, Status.serverErrorInternalError)
                }
                val respBytes = ByteArrayOutputStream().also { IppOutputStream(it).write(response) }.toByteArray()
                cout.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\n" +
                        "Content-Length: ${respBytes.size}\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                cout.write(respBytes)
                cout.flush()
                if (response.status == Status.clientErrorRequestEntityTooLarge) break
                if (head.get("Connection")?.equals("close", ignoreCase = true) == true) break
            }
        }
    }

    /**
     * Parses the IPP attribute-group packet directly off [decodedBody] — a bounded, in-memory
     * prefix — then dispatches. Print-Job/Send-Document read whatever document bytes remain
     * *directly off the same stream* into a spool file (see [streamToFile]); no operation ever
     * materializes the document as a `ByteArray`.
     *
     * [input] (the [IppInputStream] wrapping [decodedBody]) is drained to its logical end no
     * matter which branch runs or whether it throws, so a persistent connection's next pipelined
     * request always finds the raw socket stream correctly positioned right after this body.
     */
    private fun handleIpp(decodedBody: DecodedBodyInputStream, clientAddress: String?): IppPacket {
        val input = IppInputStream(decodedBody)
        try {
            val request = input.readPacket()
            return when (request.code) {
                Operation.getPrinterAttributes.code -> getPrinterAttributes(request)
                Operation.validateJob.code -> validateJob(request)
                Operation.printJob.code -> printJob(request, input, clientAddress)
                Operation.createJob.code -> createJob(request, clientAddress)
                Operation.sendDocument.code -> sendDocument(request, input)
                Operation.closeJob.code -> closeJob(request)
                Operation.getJobAttributes.code -> jobAttributes(request)
                Operation.getJobs.code -> getJobs(request)
                Operation.cancelJob.code -> cancelJob(request)
                Operation.cancelMyJobs.code -> cancelMyJobs(request)
                Operation.identifyPrinter.code -> IppPacket(Status.successfulOk, request.requestId, operationGroup())
                else -> errorResponse(request.requestId, Status.serverErrorOperationNotSupported)
            }
        } finally {
            // Attribute-only operations (Validate-Job, Create-Job, ...) never read `input`
            // themselves, so a client that incorrectly attaches trailing bytes to one of those
            // would otherwise leave them unread — drain unconditionally rather than trusting
            // each handler to fully consume its share.
            drainRemaining(input)
        }
    }

    private fun drainRemaining(input: InputStream) {
        val buf = ByteArray(65536)
        while (input.read(buf) >= 0) {
            // discard — this only runs past what a well-behaved handler already consumed.
        }
    }

    private fun getPrinterAttributes(request: IppPacket): IppPacket {
        val full = capabilities.asPrinterAttributes()
            .plus(listOf(Types.queuedJobCount.of(jobQueue.listActive().size)))
            .plus(IppMarkerAttributes.from(supplyStatusProvider()))
        val requested = request[Tag.operationAttributes]?.getValues(Types.requestedAttributes)
        val filtered = if (requested.isNullOrEmpty() || requested.contains("all")) {
            full
        } else {
            AttributeGroup.groupOf(Tag.printerAttributes, full.filter { it.name in requested })
        }
        return IppPacket(Status.successfulOk, request.requestId, operationGroup(), filtered)
    }

    private fun validateJob(request: IppPacket): IppPacket {
        val format = documentFormat(request)
        if (!isFormatSupported(format)) {
            return documentFormatNotSupported(request.requestId, format)
        }
        return IppPacket(Status.successfulOk, request.requestId, operationGroup())
    }

    private fun printJob(request: IppPacket, input: InputStream, clientAddress: String?): IppPacket {
        val requestedUri = request[Tag.operationAttributes]?.getValue(Types.printerUri)
        if (requestedUri == null) {
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        val format = documentFormat(request)
        if (!isFormatSupported(format)) {
            return documentFormatNotSupported(request.requestId, format)
        }
        spoolDir.mkdirs()
        val spool = File.createTempFile("job", spoolExtension(format), spoolDir)
        val written = streamToFile(input, spool, append = false)
        if (written == 0L) {
            spool.delete()
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName)?.value ?: "untitled"
        val jobId = jobQueue.submit(spool, name, format, clientAddress, resolveQuality(request), resolveColorMode(request))
        // Report the queue's real state — submit() only enqueues, it does not
        // guarantee the worker has started (previously this hardcoded "processing").
        val actualState = jobQueue.get(jobId)?.state ?: JobState.PENDING
        return IppPacket(
            Status.successfulOk, request.requestId,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.jobId.of(jobId),
                Types.jobUri.of(capabilities.printerUri.resolve("job/$jobId")),
                Types.jobState.of(ippState(actualState)),
                Types.jobStateReasons.of("none"),
            ),
        )
    }

    /** Create-Job: reserves a job-id before any document bytes arrive (two-phase print). */
    private fun createJob(request: IppPacket, clientAddress: String?): IppPacket {
        val requestedUri = request[Tag.operationAttributes]?.getValue(Types.printerUri)
        if (requestedUri == null) {
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        val format = documentFormat(request)
        if (!isFormatSupported(format)) {
            return documentFormatNotSupported(request.requestId, format)
        }
        spoolDir.mkdirs()
        val spool = File.createTempFile("job", spoolExtension(format), spoolDir)
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName)?.value ?: "untitled"
        val jobId = jobQueue.reserve(spool, name, format, clientAddress, resolveQuality(request), resolveColorMode(request))
        return IppPacket(
            Status.successfulOk, request.requestId,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.jobId.of(jobId),
                Types.jobUri.of(capabilities.printerUri.resolve("job/$jobId")),
                Types.jobState.of(IppJobState.pending),
                Types.jobStateReasons.of("job-incoming"),
            ),
        )
    }

    /** Send-Document: delivers the document bytes for a job reserved via Create-Job. */
    private fun sendDocument(request: IppPacket, input: InputStream): IppPacket {
        val jobId = request[Tag.operationAttributes]?.getValue(Types.jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorBadRequest)
        val job = jobQueue.get(jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorNotFound)
        // Job must still be PENDING (reserved via Create-Job, not yet handed to the
        // worker) — once it's PROCESSING/COMPLETED/etc its spool file is owned by the
        // worker (and may already be deleted), so appending here would either race the
        // worker or silently resurrect a deleted file with no size bound.
        if (job.state != JobState.PENDING) {
            return errorResponse(request.requestId, Status.clientErrorNotPossible)
        }
        try {
            streamToFile(input, job.spoolFile, append = true)
        } catch (e: BodyTooLargeException) {
            // streamToFile already truncated job.spoolFile back to its pre-call (empty)
            // length — but the job itself would otherwise stay PENDING forever: never
            // enqueued (correct — we must not race the worker with a truncated document),
            // yet also never finalized, so it shows as permanently "printing" in the
            // activity feed and never frees its queue slot. Finalize it as failed here,
            // distinctly from render/document-format failures, before the caller (handleClient)
            // turns this exception into the client-facing 413 response.
            jobQueue.fail(jobId, "request-entity-too-large")
            throw e
        }
        // We don't support multi-document jobs, so treat every Send-Document as final
        // regardless of the client's stated intent — matches Print-Job's one-shot model.
        jobQueue.enqueue(jobId)
        val actualState = jobQueue.get(jobId)?.state ?: JobState.PENDING
        return IppPacket(
            Status.successfulOk, request.requestId,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.jobId.of(jobId),
                Types.jobUri.of(capabilities.printerUri.resolve("job/$jobId")),
                Types.jobState.of(ippState(actualState)),
                Types.jobStateReasons.of("none"),
            ),
        )
    }

    /** Close-Job: no-op beyond validating the job exists — Send-Document already enqueues it. */
    private fun closeJob(request: IppPacket): IppPacket {
        val jobId = request[Tag.operationAttributes]?.getValue(Types.jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorBadRequest)
        val job = jobQueue.get(jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorNotFound)
        return IppPacket(
            Status.successfulOk, request.requestId,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.jobId.of(job.id),
                Types.jobUri.of(capabilities.printerUri.resolve("job/${job.id}")),
                Types.jobState.of(ippState(job.state)),
                Types.jobStateReasons.of(job.stateReason),
            ),
        )
    }

    private fun getJobs(request: IppPacket): IppPacket {
        val jobGroups = jobQueue.listActive().map { job ->
            groupOf(
                Tag.jobAttributes,
                Types.jobId.of(job.id),
                Types.jobUri.of(capabilities.printerUri.resolve("job/${job.id}")),
                Types.jobState.of(ippState(job.state)),
                Types.jobStateReasons.of(job.stateReason),
            )
        }
        return IppPacket(Status.successfulOk, request.requestId, *(listOf(operationGroup()) + jobGroups).toTypedArray())
    }

    private fun cancelMyJobs(request: IppPacket): IppPacket {
        jobQueue.listActive().forEach { jobQueue.cancel(it.id) }
        return IppPacket(Status.successfulOk, request.requestId, operationGroup())
    }

    private fun jobAttributes(request: IppPacket): IppPacket {
        val jobId = request[Tag.operationAttributes]?.getValue(Types.jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorBadRequest)
        val job = jobQueue.get(jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorNotFound)
        return IppPacket(
            Status.successfulOk, request.requestId,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.jobId.of(job.id),
                Types.jobUri.of(capabilities.printerUri.resolve("job/${job.id}")),
                Types.jobState.of(ippState(job.state)),
                Types.jobStateReasons.of(job.stateReason),
            ),
        )
    }

    private fun cancelJob(request: IppPacket): IppPacket {
        val jobId = request[Tag.operationAttributes]?.getValue(Types.jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorBadRequest)
        val job = jobQueue.get(jobId)
        return if (jobQueue.cancel(jobId)) {
            IppPacket(Status.successfulOk, request.requestId, operationGroup())
        } else {
            // Surface *why* it couldn't be canceled (already processing/completed/etc)
            // via status-message, the standard IPP attribute for human-readable error text.
            val reason = job?.state?.name?.lowercase() ?: "unknown"
            errorResponse(
                request.requestId, Status.clientErrorNotPossible,
                groupOf(Tag.operationAttributes, Types.statusMessage.of("job already $reason")),
            )
        }
    }

    private fun documentFormat(request: IppPacket): String =
        request[Tag.operationAttributes]?.getValue(Types.documentFormat) ?: "application/pdf"

    /** Authoritative check against the profile-declared format list — see
     *  [PrinterCapabilities.formats]. Centralized here so Validate-Job/Print-Job/Create-Job
     *  share one rule; Send-Document deliberately doesn't call this — its job's format was
     *  already validated and fixed at Print-Job/Create-Job time. */
    private fun isFormatSupported(format: String): Boolean = format in capabilities.formats

    private fun documentFormatNotSupported(requestId: Int, format: String): IppPacket = errorResponse(
        requestId, Status.clientErrorDocumentFormatNotSupported,
        groupOf(Tag.operationAttributes, Types.statusMessage.of("document-format $format is not supported")),
    )

    /** job-template attributes can legally arrive in either the job-attributes group
     *  (per RFC 8011) or the operation-attributes group — real clients aren't fully
     *  consistent here (jobName above is already read from operation-attributes for
     *  the same reason), so check both. */
    private fun <T : Any> IppPacket.jobTemplateValue(type: com.hp.jipp.encoding.AttributeType<T>): T? =
        this[Tag.jobAttributes]?.getValue(type) ?: this[Tag.operationAttributes]?.getValue(type)

    /** Missing or unsupported print-quality clamps to NORMAL — same silent-default
     *  pattern documentFormat() already uses for an unrecognized document format. */
    private fun resolveQuality(request: IppPacket): PrintQuality = when (request.jobTemplateValue(Types.printQuality)) {
        com.hp.jipp.model.PrintQuality.draft -> PrintQuality.DRAFT
        com.hp.jipp.model.PrintQuality.high -> PrintQuality.HIGH
        else -> PrintQuality.NORMAL
    }

    /** Missing/unrecognized print-color-mode, or "color" requested on a monochrome-only
     *  printer, clamps to the printer's actual default color mode. */
    private fun resolveColorMode(request: IppPacket): ColorMode {
        val requested = request.jobTemplateValue(Types.printColorMode)
        return when {
            requested == "monochrome" -> ColorMode.MONOCHROME
            requested == "color" && capabilities.color -> ColorMode.COLOR
            else -> if (capabilities.color) ColorMode.COLOR else ColorMode.MONOCHROME
        }
    }

    /**
     * Streams whatever bytes remain on [input] straight to [file] — never materializing them as
     * a `ByteArray` — either truncating (Print-Job, a fresh spool file) or appending (Send-
     * Document, onto a Create-Job-reserved file). Returns the number of bytes written.
     *
     * On failure the file is restored to the state it had before this call: deleted outright for
     * a fresh (non-append) spool file, or truncated back to its pre-call length for an append —
     * an already-reserved job's spool file isn't ours to delete, only to fail cleanly against.
     */
    private fun streamToFile(input: InputStream, file: File, append: Boolean): Long {
        val originalLength = if (append) file.length() else 0L
        var written = 0L
        try {
            FileOutputStream(file, append).use { out ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                }
            }
            return written
        } catch (e: Exception) {
            if (append) {
                try { RandomAccessFile(file, "rw").use { it.setLength(originalLength) } } catch (_: IOException) {}
            } else {
                file.delete()
            }
            throw e
        }
    }

    private fun spoolExtension(format: String): String = when (format) {
        "image/jpeg" -> ".jpg"
        "image/pwg-raster" -> ".pwg"
        else -> ".pdf"
    }

    private fun ippState(state: JobState): IppJobState = when (state) {
        JobState.PENDING -> IppJobState.pending
        JobState.PROCESSING -> IppJobState.processing
        JobState.COMPLETED -> IppJobState.completed
        JobState.ABORTED -> IppJobState.aborted
        JobState.CANCELED -> IppJobState.canceled
    }

    private fun operationGroup() = groupOf(
        Tag.operationAttributes,
        Types.attributesCharset.of("utf-8"),
        Types.attributesNaturalLanguage.of("en"),
    )

    private fun errorResponse(requestId: Int, status: Status, extra: AttributeGroup? = null): IppPacket =
        if (extra != null) IppPacket(status, requestId, operationGroup(), extra)
        else IppPacket(status, requestId, operationGroup())

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }
}
