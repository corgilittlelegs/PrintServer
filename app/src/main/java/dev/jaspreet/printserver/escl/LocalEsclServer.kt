package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.http.BodyReader
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScannerCapabilities
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private class EsclJob(val id: String, val outputFile: File) {
    @Volatile var state: String = "Processing" // "Processing" | "Completed" | "Aborted"
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
    private val performScan: (resolution: Int, colorMode: ScanColorMode, output: File) -> Unit,
    private val maxConcurrentClients: Int = 64,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    // Same rationale as LocalIppServer: bound thread growth from concurrent
    // connections so a flood of LAN connections can't OOM the app.
    private val clientSlots = Semaphore(maxConcurrentClients)
    private val currentJob = AtomicReference<EsclJob?>(null)
    private val nextJobId = AtomicInteger(1)

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
                method == "GET" && path == "/eSCL/ScannerCapabilities" ->
                    respond(cout, 200, "text/xml", EsclXml.scannerCapabilities(capabilities, makeAndModel))
                method == "GET" && path == "/eSCL/ScannerStatus" ->
                    respond(cout, 200, "text/xml", EsclXml.scannerStatus(currentJobInfo()))
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

        spoolDir.mkdirs()
        val id = nextJobId.getAndIncrement().toString()
        val output = File(spoolDir, "escl-job-$id.jpg")
        val job = EsclJob(id, output)
        if (!currentJob.compareAndSet(null, job)) {
            respond(cout, 503, "text/plain", "Scanner busy")
            return
        }
        executor.execute {
            try {
                performScan(resolution, colorMode, output)
                job.state = "Completed"
            } catch (e: Exception) {
                job.state = "Aborted"
            } finally {
                currentJob.compareAndSet(job, null)
            }
        }
        respondWithHeaders(cout, 201, "text/plain", "", mapOf("Location" to "/eSCL/ScanJobs/$id"))
    }

    private fun handleNextDocument(cout: BufferedOutputStream, path: String) {
        val id = path.removePrefix("/eSCL/ScanJobs/").removeSuffix("/NextDocument")
        val job = currentJob.get()
        val output = if (job?.id == id) job.outputFile else File(spoolDir, "escl-job-$id.jpg")
        if (!output.exists() || (job != null && job.id == id && job.state == "Processing")) {
            respond(cout, 404, "text/plain", "Not ready")
            return
        }
        respondWithHeaders(cout, 200, "image/jpeg", "", emptyMap(), bodyBytes = output.readBytes())
    }

    private fun handleDeleteJob(cout: BufferedOutputStream, path: String) {
        val id = path.removePrefix("/eSCL/ScanJobs/")
        val job = currentJob.get()
        if (job?.id == id) currentJob.compareAndSet(job, null)
        respond(cout, 200, "text/plain", "")
    }

    private fun currentJobInfo(): List<EsclJobInfo> =
        currentJob.get()?.let { listOf(EsclJobInfo(it.id, it.state)) } ?: emptyList()

    private fun parseStartLine(startLine: String): Pair<String, String>? {
        val parts = startLine.split(" ")
        if (parts.size < 2) return null
        return parts[0] to parts[1]
    }

    private fun respond(cout: BufferedOutputStream, status: Int, contentType: String, body: String) =
        respondWithHeaders(cout, status, contentType, body, emptyMap())

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
            404 -> "Not Found"; 503 -> "Service Unavailable"; else -> "Error"
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
}
