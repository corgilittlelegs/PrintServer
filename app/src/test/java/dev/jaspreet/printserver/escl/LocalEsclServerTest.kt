package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.access.ClientAccessGate
import dev.jaspreet.printserver.access.NetworkService
import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScanToneSettings
import dev.jaspreet.printserver.scan.ScannerCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocalEsclServerTest {
    private var server: LocalEsclServer? = null
    lateinit var spoolDir: java.io.File

    @After
    fun tearDown() { server?.stop() }

    private fun start(
        capabilities: ScannerCapabilities = ScannerCapabilities(
            maxWidth = 2550, maxHeight = 3300,
            supportedResolutions = listOf(300), supportedColorModes = setOf(ScanColorMode.COLOR),
        ),
        onScan: (resolution: Int, colorMode: ScanColorMode, brightness: Int, contrast: Int, output: java.io.File) -> Unit = { _, _, _, _, output ->
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte()))
        },
        defaultToneSettings: () -> ScanToneSettings = { ScanToneSettings() },
        nextDocumentPollDelayMs: Long = 250,
        maxRequestBodyBytes: Long = 64L * 1024L,
        maxScanOutputBytes: Long = 64L * 1024L * 1024L,
        maxAggregateScanBytes: Long = 256L * 1024L * 1024L,
        undeliveredRetentionMs: Long = 30 * 60_000L,
        deliveredRetentionMs: Long = 5 * 60_000L,
        clockMs: () -> Long = { System.currentTimeMillis() },
        clientAccessGate: ClientAccessGate = ClientAccessGate.ALLOW_ALL,
    ): Int {
        spoolDir = createTempDir()
        val s = LocalEsclServer(
            port = 0, makeAndModel = "PrintServer Scanner", capabilities = capabilities,
            spoolDir = spoolDir, performScan = onScan, defaultToneSettings = defaultToneSettings,
            maxRequestBodyBytes = maxRequestBodyBytes,
            maxScanOutputBytes = maxScanOutputBytes,
            maxAggregateScanBytes = maxAggregateScanBytes,
            undeliveredRetentionMs = undeliveredRetentionMs,
            deliveredRetentionMs = deliveredRetentionMs,
            clockMs = clockMs,
            nextDocumentPollDelayMs = nextDocumentPollDelayMs,
            clientAccessGate = clientAccessGate,
        )
        s.start(bindAddress = null)
        server = s
        return s.actualPort
    }

    private fun httpGet(port: Int, path: String): Pair<Int, String> {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            val text = BufferedReader(InputStreamReader(socket.getInputStream())).readText()
            val status = text.substringAfter("HTTP/1.1 ").substringBefore(" ").trim().toInt()
            val body = text.substringAfter("\r\n\r\n")
            return status to body
        }
    }

    private fun httpDelete(port: Int, path: String): Int {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("DELETE $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            val text = BufferedReader(InputStreamReader(socket.getInputStream())).readText()
            return text.substringAfter("HTTP/1.1 ").substringBefore(" ").trim().toInt()
        }
    }

    private fun httpPost(port: Int, path: String, body: String): Pair<Int, String> {
        Socket("127.0.0.1", port).use { socket ->
            val bytes = body.toByteArray()
            socket.getOutputStream().write(
                ("POST $path HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$body")
                    .toByteArray(),
            )
            val text = BufferedReader(InputStreamReader(socket.getInputStream())).readText()
            val status = text.substringAfter("HTTP/1.1 ").substringBefore(" ").trim().toInt()
            val locationHeader = text.lineSequence().firstOrNull { it.startsWith("Location:", ignoreCase = true) }
                ?.substringAfter(":")?.trim() ?: ""
            return status to locationHeader
        }
    }

    @Test
    fun `serves ScannerCapabilities reflecting the live-queried capabilities`() {
        val port = start()
        val (status, body) = httpGet(port, "/eSCL/ScannerCapabilities")
        assertEquals(200, status)
        assertTrue(body.contains("<scan:MaxWidth>2550</scan:MaxWidth>"))
        assertTrue(body.contains("RGB24"))
    }

    @Test
    fun `restricted client is rejected before request parsing or scanning`() {
        val scanned = AtomicBoolean(false)
        var observedService: NetworkService? = null
        val port = start(
            onScan = { _, _, _, _, _ -> scanned.set(true) },
            clientAccessGate = ClientAccessGate { _, service ->
                observedService = service
                false
            },
        )

        val (status, _) = httpGet(port, "/eSCL/ScannerStatus")
        assertEquals(403, status)
        assertEquals(NetworkService.ESCL_SCAN, observedService)
        assertFalse(scanned.get())
        assertTrue(spoolDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `serves ScannerStatus as Idle before any job is submitted`() {
        val port = start()
        val (status, body) = httpGet(port, "/eSCL/ScannerStatus")
        assertEquals(200, status)
        assertTrue(body.contains("<pwg:State>Idle</pwg:State>"))
    }

    @Test
    fun `POST ScanJobs starts a scan and returns a Location header`() {
        val done = CountDownLatch(1)
        val port = start(onScan = { _, _, _, _, output ->
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            done.countDown()
        })
        val (status, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertEquals(201, status)
        assertTrue(location.startsWith("/eSCL/ScanJobs/"))
        assertTrue("scan should complete", done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `POST ScanJobs forwards resolved tone settings to scan callback`() {
        val done = CountDownLatch(1)
        var capturedBrightness = -1
        var capturedContrast = -1
        val port = start(onScan = { _, _, brightness, contrast, output ->
            capturedBrightness = brightness
            capturedContrast = contrast
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            done.countDown()
        })
        val body = """
            <scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03">
              <scan:Brightness>2500</scan:Brightness>
              <scan:Contrast>750</scan:Contrast>
            </scan:ScanSettings>
        """.trimIndent()

        val (status, _) = httpPost(port, "/eSCL/ScanJobs", body)

        assertEquals(201, status)
        assertTrue("scan should complete", done.await(5, TimeUnit.SECONDS))
        assertEquals(2000, capturedBrightness)
        assertEquals(750, capturedContrast)
    }

    @Test
    fun `POST ScanJobs uses app tone defaults when client omits tone settings`() {
        val done = CountDownLatch(1)
        var capturedBrightness = -1
        var capturedContrast = -1
        val port = start(
            defaultToneSettings = { ScanToneSettings(brightness = 1150, contrast = 850) },
            onScan = { _, _, brightness, contrast, output ->
                capturedBrightness = brightness
                capturedContrast = contrast
                output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
                done.countDown()
            },
        )

        val (status, _) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")

        assertEquals(201, status)
        assertTrue("scan should complete", done.await(5, TimeUnit.SECONDS))
        assertEquals(1150, capturedBrightness)
        assertEquals(850, capturedContrast)
    }

    @Test
    fun `POST ScanJobs rejects oversized request bodies without starting a scan`() {
        val scanStarted = AtomicBoolean(false)
        val port = start(
            maxRequestBodyBytes = 16,
            onScan = { _, _, _, _, output ->
                scanStarted.set(true)
                output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            },
        )

        val (status, _) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")

        assertEquals(413, status)
        assertFalse(scanStarted.get())
    }

    @Test
    fun `oversized scan output is aborted and deleted before delivery`() {
        val port = start(
            maxScanOutputBytes = 4,
            onScan = { _, _, _, _, output -> output.writeBytes(ByteArray(5)) },
        )
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        var attempts = 40
        while (attempts-- > 0) {
            val (_, statusBody) = httpGet(port, "/eSCL/ScannerStatus")
            if (!statusBody.contains("Processing")) break
            Thread.sleep(25)
        }

        val (status, _) = httpGet(port, "$location/NextDocument")
        assertTrue(status != 200)
        assertFalse(java.io.File(spoolDir, "escl-job-${location.substringAfterLast("/")}.jpg").exists())
    }

    @Test
    fun `expired undelivered scan is removed from status and disk`() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000L)
        val port = start(
            undeliveredRetentionMs = 100,
            clockMs = { now.get() },
            onScan = { _, _, _, _, output -> output.writeBytes(byteArrayOf(1, 2, 3)) },
        )
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        val output = java.io.File(spoolDir, "escl-job-${location.substringAfterLast("/")}.jpg")
        var attempts = 40
        while (!output.exists() && attempts-- > 0) Thread.sleep(25)
        assertTrue(output.exists())

        now.set(1_101L)
        val (_, statusBody) = httpGet(port, "/eSCL/ScannerStatus")

        assertFalse(statusBody.contains(location))
        assertFalse(output.exists())
    }

    @Test
    fun `POST ScanJobs clamps above-maximum requested resolution to maximum supported dpi`() {
        val done = CountDownLatch(1)
        var capturedResolution = -1
        val port = start(
            capabilities = ScannerCapabilities(
                maxWidth = 2550,
                maxHeight = 3300,
                supportedResolutions = listOf(75, 300, 600, 1200),
                supportedColorModes = setOf(ScanColorMode.GRAYSCALE),
            ),
            onScan = { resolution, _, _, _, output ->
                capturedResolution = resolution
                output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
                done.countDown()
            },
        )
        val body = """
            <scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03">
              <scan:XResolution>2400</scan:XResolution>
              <scan:ColorMode>Grayscale8</scan:ColorMode>
            </scan:ScanSettings>
        """.trimIndent()

        val (status, _) = httpPost(port, "/eSCL/ScanJobs", body)

        assertEquals(201, status)
        assertTrue("scan should complete", done.await(5, TimeUnit.SECONDS))
        assertEquals(1200, capturedResolution)
    }

    @Test
    fun `NextDocument serves the scanned bytes once the job completes`() {
        val port = start(onScan = { _, _, _, _, output -> output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)) })
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        // Poll ScannerStatus until the job is no longer Processing -- the scan callback
        // above returns immediately, but the server processes it on a background thread.
        var attempts = 20
        while (attempts-- > 0) {
            val (_, statusBody) = httpGet(port, "/eSCL/ScannerStatus")
            if (!statusBody.contains("Processing")) break
            Thread.sleep(50)
        }
        val (docStatus, _) = httpGet(port, "$location/NextDocument")
        assertEquals(200, docStatus)
    }

    @Test
    fun `NextDocument waits for an in-flight job and serves bytes when it completes`() {
        val scanStarted = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val nextDocumentResult = java.util.concurrent.atomic.AtomicReference<Pair<Int, String>>()
        val port = start(
            nextDocumentPollDelayMs = 20,
            onScan = { _, _, _, _, output ->
                scanStarted.countDown()
                releaseScan.await(5, TimeUnit.SECONDS)
                output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01))
            },
        )
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertTrue("scan should have started", scanStarted.await(5, TimeUnit.SECONDS))

        val fetchThread = Thread {
            nextDocumentResult.set(httpGet(port, "$location/NextDocument"))
        }
        fetchThread.start()
        Thread.sleep(100)
        assertTrue("NextDocument should still be waiting while scan is processing", fetchThread.isAlive)

        releaseScan.countDown()
        fetchThread.join(5_000)

        assertEquals(200, nextDocumentResult.get().first)
    }

    @Test
    fun `ScannerStatus retains a completed job until the client deletes it`() {
        val port = start(onScan = { _, _, _, _, output -> output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)) })
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        val jobId = location.substringAfterLast("/")

        var statusBody = ""
        var attempts = 20
        while (attempts-- > 0) {
            val (_, body) = httpGet(port, "/eSCL/ScannerStatus")
            statusBody = body
            if (body.contains("<pwg:JobState>Completed</pwg:JobState>")) break
            Thread.sleep(50)
        }

        assertTrue(statusBody.contains("<pwg:State>Idle</pwg:State>"))
        assertTrue(statusBody.contains("<pwg:JobUri>/eSCL/ScanJobs/$jobId</pwg:JobUri>"))
        assertTrue(statusBody.contains("<pwg:JobState>Completed</pwg:JobState>"))
        assertTrue(statusBody.contains("<pwg:ImagesCompleted>1</pwg:ImagesCompleted>"))
        assertTrue(statusBody.contains("<pwg:ImagesToTransfer>1</pwg:ImagesToTransfer>"))

        val (docStatus, _) = httpGet(port, "$location/NextDocument")
        assertEquals(200, docStatus)
        val (_, afterFetchStatus) = httpGet(port, "/eSCL/ScannerStatus")
        assertTrue(afterFetchStatus.contains("<pwg:JobUri>/eSCL/ScanJobs/$jobId</pwg:JobUri>"))
        assertTrue(afterFetchStatus.contains("<pwg:ImagesToTransfer>0</pwg:ImagesToTransfer>"))

        val deleteStatus = httpDelete(port, location)
        assertEquals(200, deleteStatus)
        val (_, afterDeleteStatus) = httpGet(port, "/eSCL/ScannerStatus")
        assertTrue(!afterDeleteStatus.contains("<pwg:JobUri>/eSCL/ScanJobs/$jobId</pwg:JobUri>"))
    }

    @Test
    fun `NextDocument rejects repeat fetches that start after first delivery`() {
        val port = start(onScan = { _, _, _, _, output -> output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)) })
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        var attempts = 20
        while (attempts-- > 0) {
            val (_, statusBody) = httpGet(port, "/eSCL/ScannerStatus")
            if (!statusBody.contains("Processing")) break
            Thread.sleep(50)
        }

        val (firstStatus, _) = httpGet(port, "$location/NextDocument")
        val (secondStatus, _) = httpGet(port, "$location/NextDocument")

        assertEquals(200, firstStatus)
        assertTrue("a new request after successful delivery should not replay forever", secondStatus != 200)

        val deleteStatus = httpDelete(port, location)
        assertEquals(200, deleteStatus)
        val (afterDeleteStatus, _) = httpGet(port, "$location/NextDocument")
        assertTrue("DELETE ends the job's document lifetime", afterDeleteStatus != 200)
    }

    @Test
    fun `NextDocument serves overlapping requests that were waiting before first delivery`() {
        val scanStarted = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val firstResult = java.util.concurrent.atomic.AtomicReference<Pair<Int, String>>()
        val secondResult = java.util.concurrent.atomic.AtomicReference<Pair<Int, String>>()
        val port = start(
            nextDocumentPollDelayMs = 20,
            onScan = { _, _, _, _, output ->
                scanStarted.countDown()
                releaseScan.await(5, TimeUnit.SECONDS)
                output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01))
            },
        )
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertTrue("scan should have started", scanStarted.await(5, TimeUnit.SECONDS))

        val firstFetch = Thread { firstResult.set(httpGet(port, "$location/NextDocument")) }
        val secondFetch = Thread { secondResult.set(httpGet(port, "$location/NextDocument")) }
        firstFetch.start()
        secondFetch.start()
        Thread.sleep(100)
        assertTrue("first NextDocument should still be waiting while scan is processing", firstFetch.isAlive)
        assertTrue("second NextDocument should still be waiting while scan is processing", secondFetch.isAlive)

        releaseScan.countDown()
        firstFetch.join(5_000)
        secondFetch.join(5_000)

        assertEquals(200, firstResult.get().first)
        assertEquals(200, secondResult.get().first)

        val (lateStatus, _) = httpGet(port, "$location/NextDocument")
        assertTrue("late fetches after delivery must not loop forever", lateStatus != 200)
    }

    @Test
    fun `a second POST while a job is in flight is rejected`() {
        val holdLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val port = start(onScan = { _, _, _, _, output ->
            holdLatch.countDown()
            releaseLatch.await(5, TimeUnit.SECONDS)
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        })
        httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertTrue(holdLatch.await(5, TimeUnit.SECONDS))
        val (secondStatus, _) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertEquals(503, secondStatus)
        releaseLatch.countDown()
    }

    @Test
    fun `DELETE of an in-flight job does not free the scanner slot while it is still processing`() {
        val holdLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val port = start(onScan = { _, _, _, _, output ->
            holdLatch.countDown()
            releaseLatch.await(5, TimeUnit.SECONDS)
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        })
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertTrue(holdLatch.await(5, TimeUnit.SECONDS))

        // A real eSCL client (sane-airscan, Apple Image Capture) may send DELETE to cancel
        // an in-progress scan, not just after fetching a completed one. That must not
        // advertise the scanner as free while performScan is still actively running on the
        // shared UsbTransport -- otherwise a second POST landing right after would start a
        // second concurrent performScan call and corrupt both scans' USB framing.
        val deleteStatus = httpDelete(port, location)
        assertEquals(200, deleteStatus)

        val (_, statusBody) = httpGet(port, "/eSCL/ScannerStatus")
        assertTrue(statusBody.contains("<pwg:State>Processing</pwg:State>"))
        assertTrue(statusBody.contains("<pwg:JobUri>$location</pwg:JobUri>"))

        val (secondStatus, _) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertEquals(
            "second POST must still be rejected -- the in-flight job's slot must not have been freed by DELETE",
            503,
            secondStatus,
        )
        releaseLatch.countDown()
    }

    @Test
    fun `DELETE removes the spooled output file from disk`() {
        val port = start(onScan = { _, _, _, _, output -> output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)) })
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        var attempts = 20
        while (attempts-- > 0) {
            val (_, statusBody) = httpGet(port, "/eSCL/ScannerStatus")
            if (!statusBody.contains("Processing")) break
            Thread.sleep(50)
        }
        val jobId = location.substringAfterLast("/")
        val output = java.io.File(spoolDir, "escl-job-$jobId.jpg")
        assertTrue("spooled file should exist after scan completes", output.exists())

        val deleteStatus = httpDelete(port, location)
        assertEquals(200, deleteStatus)
        assertTrue("spooled file should be deleted after DELETE", !output.exists())
    }

    @Test
    fun `NextDocument does not serve a 200 for an aborted job's partial bytes`() {
        val port = start(onScan = { _, _, _, _, output ->
            // Simulate a partial/truncated write followed by a mid-scan failure, e.g. a
            // USB write error -- the file exists on disk but the job is Aborted.
            output.writeBytes(byteArrayOf(0xFF.toByte()))
            throw java.io.IOException("simulated USB write failure")
        })
        val (_, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        var attempts = 20
        while (attempts-- > 0) {
            val (_, statusBody) = httpGet(port, "/eSCL/ScannerStatus")
            if (!statusBody.contains("Processing")) break
            Thread.sleep(50)
        }
        val (docStatus, _) = httpGet(port, "$location/NextDocument")
        assertTrue("aborted job must not be served as a successful 200 scan", docStatus != 200)
    }

    @Test
    fun `evicting a retained completed job deletes its spooled output file`() {
        // The jobs map is capped at 200 retained (MAX_RETAINED_JOBS) so a client that
        // never sends DELETE doesn't leak spooled files forever -- submit 201 scans
        // sequentially (this server allows only one in flight at a time) and confirm
        // the oldest job's output file is evicted from disk once the cap is exceeded.
        val port = start(onScan = { _, _, _, _, output -> output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) })

        fun runOneScanToCompletion(): String {
            // Retry on 503 rather than assuming the previous job's slot is already free --
            // currentJob is only cleared once the prior job's bookkeeping (eviction/cleanup)
            // has finished, so a POST landing right after a completion may still race it.
            var location = ""
            var postAttempts = 200
            while (postAttempts-- > 0) {
                val (status, loc) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
                if (status == 201) { location = loc; break }
                Thread.sleep(20)
            }
            check(location.isNotEmpty()) { "scanner never became free for a new POST" }
            var attempts = 200
            while (attempts-- > 0) {
                val (_, statusBody) = httpGet(port, "/eSCL/ScannerStatus")
                if (!statusBody.contains("Processing")) break
                Thread.sleep(20)
            }
            return location.substringAfterLast("/")
        }

        val firstJobId = runOneScanToCompletion()
        val firstOutput = java.io.File(spoolDir, "escl-job-$firstJobId.jpg")
        assertTrue("first job's spooled file should exist after its scan completes", firstOutput.exists())

        repeat(200) { runOneScanToCompletion() }

        // Eviction runs as part of the 201st job's own background completion bookkeeping,
        // which can still be in flight for a moment after ScannerStatus already reports
        // that job as no-longer-Processing (state flips to COMPLETED before the eviction
        // step that follows it in the same finally block) -- so poll rather than assert
        // immediately.
        var attempts = 100
        while (firstOutput.exists() && attempts-- > 0) {
            Thread.sleep(20)
        }
        assertTrue(
            "oldest job's spooled file should have been evicted from disk",
            !firstOutput.exists(),
        )
    }

    @Test
    fun `stop waits for an in-flight scan to finish before returning`() {
        val scanStarted = CountDownLatch(1)
        val released = AtomicBoolean(false)
        // Mirrors the real USB scan transport, which blocks in native I/O and does not
        // unblock on Thread.interrupt() -- stop()'s executor.shutdownNow() interrupts the
        // pool thread running performScan, but a real in-flight scan write should keep
        // running rather than being abandoned mid-transfer.
        val port = start(onScan = { _, _, _, _, output ->
            scanStarted.countDown()
            while (!released.get()) {
                try { Thread.sleep(20) } catch (_: InterruptedException) { /* not abandoned mid-scan */ }
            }
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        })
        httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertTrue("scan should have started", scanStarted.await(5, TimeUnit.SECONDS))

        val stopReturned = AtomicBoolean(false)
        val stopThread = Thread { server?.stop(); stopReturned.set(true) }
        stopThread.start()
        Thread.sleep(200)
        assertFalse("stop must not return while a scan is still in flight", stopReturned.get())

        released.set(true)
        stopThread.join(5_000)
        assertTrue("stop should have returned once the scan finished", stopReturned.get())
    }
}
