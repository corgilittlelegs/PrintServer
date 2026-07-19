package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScanColorMode
import dev.jaspreet.printserver.scan.ScannerCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        onScan: (resolution: Int, colorMode: ScanColorMode, output: java.io.File) -> Unit = { _, _, output ->
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte()))
        },
    ): Int {
        spoolDir = createTempDir()
        val s = LocalEsclServer(
            port = 0, makeAndModel = "PrintServer Scanner", capabilities = capabilities,
            spoolDir = spoolDir, performScan = onScan,
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
    fun `serves ScannerStatus as Idle before any job is submitted`() {
        val port = start()
        val (status, body) = httpGet(port, "/eSCL/ScannerStatus")
        assertEquals(200, status)
        assertTrue(body.contains("<scan:State>Idle</scan:State>"))
    }

    @Test
    fun `POST ScanJobs starts a scan and returns a Location header`() {
        val done = CountDownLatch(1)
        val port = start(onScan = { _, _, output ->
            output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            done.countDown()
        })
        val (status, location) = httpPost(port, "/eSCL/ScanJobs", "<scan:ScanSettings></scan:ScanSettings>")
        assertEquals(201, status)
        assertTrue(location.startsWith("/eSCL/ScanJobs/"))
        assertTrue("scan should complete", done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `NextDocument serves the scanned bytes once the job completes`() {
        val port = start(onScan = { _, _, output -> output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)) })
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
    fun `a second POST while a job is in flight is rejected`() {
        val holdLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val port = start(onScan = { _, _, output ->
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
    fun `DELETE removes the spooled output file from disk`() {
        val port = start(onScan = { _, _, output -> output.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)) })
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
        val port = start(onScan = { _, _, output ->
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
}
