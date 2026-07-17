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
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.jobs.JobState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

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
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    val actualPort: Int get() = serverSocket?.localPort ?: port

    fun start(bindAddress: InetAddress?) {
        val ss = ServerSocket(port, 50, bindAddress)
        serverSocket = ss
        executor.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                executor.execute { handleClient(client) }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 30_000
        client.use {
            val cin = BufferedInputStream(client.getInputStream())
            val cout = BufferedOutputStream(client.getOutputStream())
            while (true) {
                val head = try { HttpHead.parse(cin) ?: break } catch (_: IOException) { break }
                val response = try {
                    val body = BodyReader.readAll(head, cin, maxDocumentBytes)
                    handleIpp(body)
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

    private fun handleIpp(body: ByteArray): IppPacket {
        val input = IppInputStream(ByteArrayInputStream(body))
        val request = input.readPacket()
        // Any bytes after the IPP packet are the document payload (Print-Job).
        val document = input.readBytes()

        return when (request.code) {
            Operation.getPrinterAttributes.code -> getPrinterAttributes(request)
            Operation.validateJob.code -> IppPacket(
                Status.successfulOk, request.requestId, operationGroup(),
            )
            Operation.printJob.code -> printJob(request, document)
            Operation.createJob.code -> createJob(request)
            Operation.sendDocument.code -> sendDocument(request, document)
            Operation.closeJob.code -> closeJob(request)
            Operation.getJobAttributes.code -> jobAttributes(request)
            Operation.getJobs.code -> getJobs(request)
            Operation.cancelJob.code -> cancelJob(request)
            Operation.cancelMyJobs.code -> cancelMyJobs(request)
            Operation.identifyPrinter.code -> IppPacket(Status.successfulOk, request.requestId, operationGroup())
            else -> errorResponse(request.requestId, Status.serverErrorOperationNotSupported)
        }
    }

    private fun getPrinterAttributes(request: IppPacket): IppPacket {
        val full = capabilities.asPrinterAttributes()
        val requested = request[Tag.operationAttributes]?.getValues(Types.requestedAttributes)
        val filtered = if (requested.isNullOrEmpty() || requested.contains("all")) {
            full
        } else {
            AttributeGroup.groupOf(Tag.printerAttributes, full.filter { it.name in requested })
        }
        return IppPacket(Status.successfulOk, request.requestId, operationGroup(), filtered)
    }

    private fun printJob(request: IppPacket, document: ByteArray): IppPacket {
        if (document.isEmpty()) {
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        val requestedUri = request[Tag.operationAttributes]?.getValue(Types.printerUri)
        if (requestedUri == null) {
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        val format = documentFormat(request)
        spoolDir.mkdirs()
        val spool = File.createTempFile("job", spoolExtension(format), spoolDir)
        spool.writeBytes(document)
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName)?.value ?: "untitled"
        val jobId = jobQueue.submit(spool, name, format)
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
    private fun createJob(request: IppPacket): IppPacket {
        val requestedUri = request[Tag.operationAttributes]?.getValue(Types.printerUri)
        if (requestedUri == null) {
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        val format = documentFormat(request)
        spoolDir.mkdirs()
        val spool = File.createTempFile("job", spoolExtension(format), spoolDir)
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName)?.value ?: "untitled"
        val jobId = jobQueue.reserve(spool, name, format)
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
    private fun sendDocument(request: IppPacket, document: ByteArray): IppPacket {
        val jobId = request[Tag.operationAttributes]?.getValue(Types.jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorBadRequest)
        val job = jobQueue.get(jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorNotFound)
        if (document.isNotEmpty()) job.spoolFile.appendBytes(document)
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

    private fun spoolExtension(format: String): String = when (format) {
        "image/jpeg" -> ".jpg"
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
