package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.IntType
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.render.FakeRenderingPipeline
import dev.jaspreet.printserver.scan.SupplyCartridge
import dev.jaspreet.printserver.scan.SupplyStatus
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LocalIppServerTest {

    private var server: LocalIppServer? = null
    private var queue: JobQueue? = null
    private var spoolDir: File? = null

    @After
    fun tearDown() {
        server?.stop()
        queue?.shutdown()
    }

    private fun start(
        pipeline: dev.jaspreet.printserver.render.RenderingPipeline = FakeRenderingPipeline(),
        capabilities: PrinterCapabilities = PrinterCapabilities.deskJet2300(URI.create("ipp://127.0.0.1:0/ipp/print")),
        supplyStatusProvider: () -> SupplyStatus? = { null },
    ): Int {
        val q = JobQueue(pipeline, { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val dir = createTempDir()
        spoolDir = dir
        val s = LocalIppServer(
            port = 0,
            capabilities = capabilities,
            jobQueue = q,
            spoolDir = dir,
            supplyStatusProvider = supplyStatusProvider,
        )
        s.start(bindAddress = null)
        server = s
        return s.actualPort
    }

    private fun ipp(port: Int, packet: IppPacket, document: ByteArray = ByteArray(0)): IppPacket {
        val body = ByteArrayOutputStream()
        IppOutputStream(body).write(packet)
        body.write(document)
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/ipp")
        conn.outputStream.use { it.write(body.toByteArray()) }
        assertEquals(200, conn.responseCode)
        return conn.inputStream.use { IppInputStream(it).readPacket() }
    }

    private fun operationGroup() = groupOf(
        Tag.operationAttributes,
        Types.attributesCharset.of("utf-8"),
        Types.attributesNaturalLanguage.of("en"),
        Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
    )

    @Test
    fun `answers get-printer-attributes with driverless raster support`() {
        val port = start()
        val resp = ipp(port, IppPacket(Operation.getPrinterAttributes, 1, operationGroup()))
        assertEquals(Status.successfulOk, resp.status)
        val formats = resp[Tag.printerAttributes]!!.getValues(Types.documentFormatSupported)
        assertEquals(listOf("application/pdf", "image/pwg-raster", "image/jpeg"), formats)
    }

    @Test
    fun `print-job spools document and returns job id`() {
        val port = start()
        val resp = ipp(
            port,
            IppPacket(Operation.printJob, 2, operationGroup()),
            "%PDF-1.4 fake".toByteArray(),
        )
        assertEquals(Status.successfulOk, resp.status)
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)
        assertNotNull(jobId)
        assertNotNull(queue!!.get(jobId!!))
    }

    @Test
    fun `print-job preserves pwg raster document format for renderer`() {
        val port = start()
        val request = IppPacket(
            Operation.printJob, 22,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.documentFormat.of("image/pwg-raster"),
            ),
        )
        val resp = ipp(port, request, "RaS2 fake raster".toByteArray())
        assertEquals(Status.successfulOk, resp.status)
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        val job = queue!!.get(jobId)!!
        assertEquals("image/pwg-raster", job.format)
        assertEquals("pwg", job.spoolFile.extension)
    }

    @Test
    fun `get-job-attributes reports job state`() {
        val port = start()
        val submit = ipp(port, IppPacket(Operation.printJob, 3, operationGroup()), "%PDF".toByteArray())
        val jobId = submit[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        val query = IppPacket(
            Operation.getJobAttributes, 4,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.jobId.of(jobId),
            ),
        )
        val resp = ipp(port, query)
        assertEquals(Status.successfulOk, resp.status)
        assertNotNull(resp[Tag.jobAttributes]!!.getValue(Types.jobState))
    }

    @Test
    fun `unsupported operation returns error status`() {
        val port = start()
        val resp = ipp(port, IppPacket(Operation.pausePrinter, 5, operationGroup()))
        assertEquals(Status.serverErrorOperationNotSupported, resp.status)
    }

    @Test
    fun `print-job reports the job's real queue state, not a hardcoded value`() {
        // Single-worker queue: a first job that blocks forever occupies the
        // worker, so a second submitted job is *deterministically* PENDING —
        // no race with the worker thread, unlike reading state right after
        // submit() (which can legitimately observe PENDING or PROCESSING
        // depending on how fast the worker gets scheduled). If printJob()
        // still hardcoded "processing" for every job, this would fail.
        val firstStarted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockOnFirst = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(
                document: File, output: File, format: String,
                quality: dev.jaspreet.printserver.jobs.PrintQuality, colorMode: dev.jaspreet.printserver.jobs.ColorMode,
            ) {
                firstStarted.countDown()
                release.await()
            }
        }
        val port = start(blockOnFirst)
        ipp(port, IppPacket(Operation.printJob, 6, operationGroup()), "%PDF".toByteArray())
        assertTrue(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val secondResp = ipp(port, IppPacket(Operation.printJob, 7, operationGroup()), "%PDF".toByteArray())
        val reportedState = secondResp[Tag.jobAttributes]!!.getValue(Types.jobState)
        assertEquals(
            "reported jobState must come from the real queue, not a hardcoded value",
            com.hp.jipp.model.JobState.pending,
            reportedState,
        )
        release.countDown()
    }

    @Test
    fun `get-printer-attributes honors requested-attributes and omits the rest`() {
        val port = start()
        val request = IppPacket(
            Operation.getPrinterAttributes, 7,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.requestedAttributes.of("printer-name"),
            ),
        )
        val resp = ipp(port, request)
        assertEquals(Status.successfulOk, resp.status)
        val group = resp[Tag.printerAttributes]!!
        assertNotNull(group.getValue(Types.printerName))
        assertEquals(
            "only the requested attribute should be present",
            null,
            group.getValue(Types.documentFormatSupported),
        )
    }

    @Test
    fun `get-printer-attributes reports the real queued-job-count, not a hardcoded value`() {
        val firstStarted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockOnFirst = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(
                document: File, output: File, format: String,
                quality: dev.jaspreet.printserver.jobs.PrintQuality, colorMode: dev.jaspreet.printserver.jobs.ColorMode,
            ) {
                firstStarted.countDown()
                release.await()
            }
        }
        val port = start(blockOnFirst)
        ipp(port, IppPacket(Operation.printJob, 9, operationGroup()), "%PDF".toByteArray())
        assertTrue(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        ipp(port, IppPacket(Operation.printJob, 10, operationGroup()), "%PDF".toByteArray())

        val resp = ipp(port, IppPacket(Operation.getPrinterAttributes, 11, operationGroup()))
        assertEquals(2, resp[Tag.printerAttributes]!!.getValue(Types.queuedJobCount))
        release.countDown()
    }

    @Test
    fun `get-printer-attributes includes supply marker attributes when available`() {
        val port = start(
            supplyStatusProvider = {
                SupplyStatus(
                    cartridges = listOf(
                        SupplyCartridge(name = "Black cartridge", color = "Black", levelPercent = 63),
                        SupplyCartridge(name = "Tri color cartridge", color = "Cyan Magenta Yellow", levelPercent = 12),
                    ),
                    sourcePath = "/DevMgmt/ConsumableConfigDyn.xml",
                )
            },
        )

        val resp = ipp(port, IppPacket(Operation.getPrinterAttributes, 12, operationGroup()))
        val group = resp[Tag.printerAttributes]!!

        assertEquals(listOf("Black cartridge", "Tri color cartridge"), group["marker-names"]!!.strings())
        assertEquals(listOf("#000000", "#00FFFF#FF00FF#FFFF00"), group["marker-colors"]!!.strings())
        assertEquals(
            listOf(63, 12),
            IntType.Set("marker-levels").coerce(group["marker-levels"]!!)!!.toList(),
        )
    }

    @Test
    fun `get-printer-attributes can return only requested marker attributes`() {
        val port = start(
            supplyStatusProvider = {
                SupplyStatus(
                    cartridges = listOf(SupplyCartridge(name = "Black cartridge", color = "Black", levelPercent = 63)),
                    sourcePath = "/DevMgmt/ConsumableConfigDyn.xml",
                )
            },
        )
        val request = IppPacket(
            Operation.getPrinterAttributes, 13,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.requestedAttributes.of("marker-levels"),
            ),
        )

        val resp = ipp(port, request)
        val group = resp[Tag.printerAttributes]!!

        assertEquals(listOf(63), IntType.Set("marker-levels").coerce(group["marker-levels"]!!)!!.toList())
        assertEquals(null, group["marker-names"])
        assertEquals(null, group.getValue(Types.printerName))
    }

    @Test
    fun `oversized document is rejected with an ipp error, not a dropped connection`() {
        val port = start()
        // BodyReader's limit is enforced inside LocalIppServer; a body of a
        // few KB is nowhere near a real 200MB cap, so the server is
        // constructed with a tiny limit for this test via the same field
        // LocalIppServer exposes (see Step 3's maxDocumentBytes constructor param).
        val q = JobQueue(FakeRenderingPipeline(), { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val caps = PrinterCapabilities.deskJet2300(URI.create("ipp://127.0.0.1:0/ipp/print"))
        val tinyLimitServer = LocalIppServer(
            port = 0, capabilities = caps, jobQueue = q, spoolDir = createTempDir(), maxDocumentBytes = 16,
        )
        tinyLimitServer.start(bindAddress = null)
        server = tinyLimitServer
        val resp = ipp(
            tinyLimitServer.actualPort,
            IppPacket(Operation.printJob, 8, operationGroup()),
            "this document is definitely over sixteen bytes".toByteArray(),
        )
        assertEquals(Status.clientErrorRequestEntityTooLarge, resp.status)
    }

    @Test
    fun `too-large print-job body is rejected before a job exists, before any render, and leaves no spool file`() {
        val pipeline = FakeRenderingPipeline()
        val q = JobQueue(pipeline, { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val dir = createTempDir()
        val caps = PrinterCapabilities.deskJet2300(URI.create("ipp://127.0.0.1:0/ipp/print"))
        val tinyLimitServer = LocalIppServer(
            port = 0, capabilities = caps, jobQueue = q, spoolDir = dir, maxDocumentBytes = 16,
        )
        tinyLimitServer.start(bindAddress = null)
        server = tinyLimitServer

        val resp = ipp(
            tinyLimitServer.actualPort,
            IppPacket(Operation.printJob, 9, operationGroup()),
            "this document is definitely over sixteen bytes".toByteArray(),
        )
        assertEquals(Status.clientErrorRequestEntityTooLarge, resp.status)

        // No jobId in the response, and no job was ever created — the body-size gate rejects
        // the request while parsing the HTTP body, before JobQueue.submit() is ever reached.
        assertEquals(null, resp[Tag.jobAttributes]?.getValue(Types.jobId))
        assertEquals(
            "no jobs should have been created for a too-large body",
            emptyList<dev.jaspreet.printserver.jobs.PrintJob>(),
            q.listActive(),
        )

        // pipeline.render() must never have been invoked — a too-large body is rejected
        // at the HTTP layer, well before checkFreeSpace()/pipeline.render() would run.
        assertTrue("render() must not run for a rejected too-large body", pipeline.rendered.isEmpty())

        // No spool file (partial or otherwise) should be left behind — File.createTempFile
        // for the job's spool is never reached because the body is rejected while it's
        // still being read directly off the socket.
        assertEquals(
            "no spool file should exist for a request that never became a job",
            0,
            dir.listFiles()?.size ?: 0,
        )
    }

    @Test
    fun `print-job resolves print-quality and print-color-mode from the request`() {
        val port = start()
        val request = IppPacket(
            Operation.printJob, 30,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.printQuality.of(com.hp.jipp.model.PrintQuality.high),
                Types.printColorMode.of("monochrome"),
            ),
        )
        val resp = ipp(port, request, "%PDF".toByteArray())
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        val job = queue!!.get(jobId)!!
        assertEquals(dev.jaspreet.printserver.jobs.PrintQuality.HIGH, job.quality)
        assertEquals(dev.jaspreet.printserver.jobs.ColorMode.MONOCHROME, job.colorMode)
    }

    @Test
    fun `print-job defaults to NORMAL quality and the printer's default color when attrs are absent`() {
        val port = start()
        val resp = ipp(port, IppPacket(Operation.printJob, 31, operationGroup()), "%PDF".toByteArray())
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        val job = queue!!.get(jobId)!!
        assertEquals(dev.jaspreet.printserver.jobs.PrintQuality.NORMAL, job.quality)
        assertEquals(dev.jaspreet.printserver.jobs.ColorMode.COLOR, job.colorMode) // deskJet2300 supports color
    }

    @Test
    fun `print-job clamps an unrecognized print-color-mode to the printer's default`() {
        val port = start()
        val request = IppPacket(
            Operation.printJob, 32,
            operationGroup(),
            groupOf(Tag.jobAttributes, Types.printColorMode.of("sepia")),
        )
        val resp = ipp(port, request, "%PDF".toByteArray())
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        assertEquals(dev.jaspreet.printserver.jobs.ColorMode.COLOR, queue!!.get(jobId)!!.colorMode)
    }

    @Test
    fun `validate-job rejects unsupported document-format`() {
        val port = start()
        val request = IppPacket(
            Operation.validateJob, 40,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.documentFormat.of("application/postscript"),
            ),
        )
        val resp = ipp(port, request)
        assertEquals(Status.clientErrorDocumentFormatNotSupported, resp.status)
    }

    @Test
    fun `validate-job accepts supported document-format`() {
        val port = start()
        val request = IppPacket(
            Operation.validateJob, 41,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.documentFormat.of("application/pdf"),
            ),
        )
        val resp = ipp(port, request)
        assertEquals(Status.successfulOk, resp.status)
    }

    @Test
    fun `print-job rejects unsupported document-format without touching the filesystem`() {
        val port = start()
        val request = IppPacket(
            Operation.printJob, 42,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.documentFormat.of("application/vnd.hp-pcl"),
            ),
        )
        val resp = ipp(port, request, "some bytes".toByteArray())
        assertEquals(Status.clientErrorDocumentFormatNotSupported, resp.status)
        assertEquals(null, resp[Tag.jobAttributes]?.getValue(Types.jobId))
        // Rejection must happen before File.createTempFile — no spool file should
        // ever be written for a format the printer can't handle.
        val filesInSpoolDir: List<File> = spoolDir!!.listFiles()?.toList() ?: emptyList()
        assertEquals(
            "no spool file should be created for a rejected format",
            0,
            filesInSpoolDir.size,
        )
    }

    @Test
    fun `create-job rejects unsupported document-format`() {
        val port = start()
        val request = IppPacket(
            Operation.createJob, 43,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.documentFormat.of("image/urf"),
            ),
        )
        val resp = ipp(port, request)
        assertEquals(Status.clientErrorDocumentFormatNotSupported, resp.status)
        assertEquals(null, resp[Tag.jobAttributes]?.getValue(Types.jobId))
    }

    @Test
    fun `print-job rejects application-octet-stream document-format`() {
        val port = start()
        val request = IppPacket(
            Operation.printJob, 44,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.documentFormat.of("application/octet-stream"),
            ),
        )
        val resp = ipp(port, request, "some bytes".toByteArray())
        assertEquals(Status.clientErrorDocumentFormatNotSupported, resp.status)
    }

    @Test
    fun `validate-job rejects an unknown mime type`() {
        val port = start()
        val request = IppPacket(
            Operation.validateJob, 45,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.documentFormat.of("application/x-totally-made-up"),
            ),
        )
        val resp = ipp(port, request)
        assertEquals(Status.clientErrorDocumentFormatNotSupported, resp.status)
    }

    @Test
    fun `print-job clamps a color request to monochrome on a monochrome-only printer`() {
        val monoCaps = PrinterCapabilities(
            makeAndModel = "Mono Test Printer",
            formats = listOf("application/pdf"),
            color = false,
            printerUri = URI.create("ipp://127.0.0.1:0/ipp/print"),
            uuid = java.util.UUID.randomUUID(),
            mediaSupported = listOf("iso_a4_210x297mm"),
            mediaDefault = "iso_a4_210x297mm",
            colorModesSupported = listOf("monochrome"),
            resolutionsDpiSupported = listOf(300, 600),
            defaultResolutionDpi = 600,
            qualityModesSupported = listOf(
                com.hp.jipp.model.PrintQuality.draft,
                com.hp.jipp.model.PrintQuality.normal,
                com.hp.jipp.model.PrintQuality.high,
            ),
            qualityModeDefault = com.hp.jipp.model.PrintQuality.normal,
        )
        val port = start(capabilities = monoCaps)
        val request = IppPacket(
            Operation.printJob, 33,
            operationGroup(),
            groupOf(Tag.jobAttributes, Types.printColorMode.of("color")),
        )
        val resp = ipp(port, request, "%PDF".toByteArray())
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        assertEquals(dev.jaspreet.printserver.jobs.ColorMode.MONOCHROME, queue!!.get(jobId)!!.colorMode)
    }

    /** Captures exactly the bytes the worker actually saw for [render], before JobQueue deletes
     *  the spool file — lets tests assert on spooled content without racing the worker thread. */
    private class CapturingRenderingPipeline : dev.jaspreet.printserver.render.RenderingPipeline {
        val captured = CompletableFuture<ByteArray>()
        override fun render(
            document: File, output: File, format: String,
            quality: dev.jaspreet.printserver.jobs.PrintQuality, colorMode: dev.jaspreet.printserver.jobs.ColorMode,
        ) {
            captured.complete(document.readBytes())
            output.writeBytes(ByteArray(0))
        }
    }

    @Test
    fun `print-job spools a large document byte-for-byte via streaming, not full in-memory buffering`() {
        val pipeline = CapturingRenderingPipeline()
        val port = start(pipeline)
        // Comfortably larger than the 64KB copy buffer used by streamToFile, and larger than the
        // BufferedInputStream/DataInputStream default buffers IppInputStream wraps internally, so
        // this exercises many read/write cycles rather than a single pass.
        val document = ByteArray(2_000_000) { (it % 251).toByte() }
        val resp = ipp(port, IppPacket(Operation.printJob, 60, operationGroup()), document)
        assertEquals(Status.successfulOk, resp.status)
        val spooled = pipeline.captured.get(10, TimeUnit.SECONDS)
        assertArrayEquals(document, spooled)
    }

    @Test
    fun `create-job leaves an empty spool file`() {
        val port = start()
        val resp = ipp(port, IppPacket(Operation.createJob, 61, operationGroup()))
        assertEquals(Status.successfulOk, resp.status)
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        val job = queue!!.get(jobId)!!
        assertEquals(dev.jaspreet.printserver.jobs.JobState.PENDING, job.state)
        assertTrue("reserve()'s spool file must exist even though it's empty", job.spoolFile.exists())
        assertEquals(0L, job.spoolFile.length())
    }

    @Test
    fun `send-document streams and appends the document to the create-job-reserved spool file`() {
        val pipeline = CapturingRenderingPipeline()
        val port = start(pipeline)
        val createResp = ipp(port, IppPacket(Operation.createJob, 62, operationGroup()))
        val jobId = createResp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        assertEquals(0L, queue!!.get(jobId)!!.spoolFile.length())

        val sendRequest = IppPacket(
            Operation.sendDocument, 63,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.jobId.of(jobId),
            ),
        )
        val document = ByteArray(300_000) { (it % 199).toByte() }
        val sendResp = ipp(port, sendRequest, document)
        assertEquals(Status.successfulOk, sendResp.status)
        assertEquals(
            "our one-shot model treats every Send-Document as final",
            com.hp.jipp.model.JobState.pending,
            sendResp[Tag.jobAttributes]!!.getValue(Types.jobState),
        )

        val spooled = pipeline.captured.get(10, TimeUnit.SECONDS)
        assertArrayEquals(document, spooled)
    }

    @Test
    fun `send-document is rejected once the reserved job has left PENDING`() {
        val firstStarted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockOnFirst = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(
                document: File, output: File, format: String,
                quality: dev.jaspreet.printserver.jobs.PrintQuality, colorMode: dev.jaspreet.printserver.jobs.ColorMode,
            ) {
                firstStarted.countDown()
                release.await()
            }
        }
        val port = start(blockOnFirst)
        // Occupy the single worker so the reserved job below can never leave PENDING on its own —
        // instead we cancel it directly to force a non-PENDING state deterministically.
        ipp(port, IppPacket(Operation.printJob, 64, operationGroup()), "%PDF".toByteArray())
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

        val createResp = ipp(port, IppPacket(Operation.createJob, 65, operationGroup()))
        val jobId = createResp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        assertTrue(queue!!.cancel(jobId))

        val sendRequest = IppPacket(
            Operation.sendDocument, 66,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.jobId.of(jobId),
            ),
        )
        val sendResp = ipp(port, sendRequest, "%PDF late".toByteArray())
        assertEquals(Status.clientErrorNotPossible, sendResp.status)
        release.countDown()
    }

    /** Reads one full HTTP/IPP response (head + exactly Content-Length body bytes) off a raw socket. */
    private fun readIppResponse(input: java.io.InputStream): IppPacket {
        val head = dev.jaspreet.printserver.http.HttpHead.parse(input)
            ?: error("connection closed before a response head arrived")
        val len = head.get("Content-Length")?.trim()?.toIntOrNull()
            ?: error("response had no Content-Length")
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            check(n >= 0) { "unexpected EOF reading response body ($off/$len bytes read)" }
            off += n
        }
        return IppInputStream(ByteArrayInputStream(body)).readPacket()
    }

    private fun writeIppRequest(output: java.io.OutputStream, packet: IppPacket, document: ByteArray) {
        val body = ByteArrayOutputStream()
        IppOutputStream(body).write(packet)
        body.write(document)
        val bodyBytes = body.toByteArray()
        val head = "POST /ipp/print HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: application/ipp\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: keep-alive\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bodyBytes)
        output.flush()
    }

    @Test
    fun `two ipp requests pipelined on one persistent connection are both parsed and handled correctly`() {
        val port = start()
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            writeIppRequest(out, IppPacket(Operation.printJob, 70, operationGroup()), "%PDF first document".toByteArray())
            val resp1 = readIppResponse(input)
            assertEquals(Status.successfulOk, resp1.status)
            val jobId1 = resp1[Tag.jobAttributes]!!.getValue(Types.jobId)!!

            // If the first request's body (or its HTTP framing) weren't fully/correctly drained,
            // this second head would be misparsed off whatever bytes were left over — this is the
            // core "persistent connections" assertion for this test.
            writeIppRequest(
                out,
                IppPacket(Operation.printJob, 71, operationGroup()),
                "%PDF a completely different second document, longer than the first one".toByteArray(),
            )
            val resp2 = readIppResponse(input)
            assertEquals(Status.successfulOk, resp2.status)
            val jobId2 = resp2[Tag.jobAttributes]!!.getValue(Types.jobId)!!

            assertNotEquals("second request must get its own distinct job id", jobId1, jobId2)
            assertNotNull(queue!!.get(jobId1))
            assertNotNull(queue!!.get(jobId2))
        }
    }

    @Test
    fun `an attribute-only operation followed by a print-job on the same connection both parse correctly`() {
        val port = start()
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            writeIppRequest(out, IppPacket(Operation.getPrinterAttributes, 72, operationGroup()), ByteArray(0))
            val resp1 = readIppResponse(input)
            assertEquals(Status.successfulOk, resp1.status)

            writeIppRequest(out, IppPacket(Operation.printJob, 73, operationGroup()), "%PDF after an attribute-only request".toByteArray())
            val resp2 = readIppResponse(input)
            assertEquals(Status.successfulOk, resp2.status)
            assertNotNull(resp2[Tag.jobAttributes]!!.getValue(Types.jobId))
        }
    }

    /** Writes a chunked-encoded IPP request whose body is [packetBytes] followed by each of
     *  [documentChunks], one HTTP chunk per element — lets a test control exactly how many
     *  document bytes have reached the server (and been spooled) before a later chunk pushes
     *  the cumulative body size over a cap, unlike the fixed-Content-Length `ipp()`/
     *  `writeIppRequest()` helpers (whose over-cap case is rejected before any file is created —
     *  see the Content-Length branch of DecodedBodyInputStream's constructor). */
    private fun writeChunkedIppRequest(output: java.io.OutputStream, packetBytes: ByteArray, documentChunks: List<ByteArray>) {
        val head = "POST /ipp/print HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: application/ipp\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Connection: keep-alive\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        for (c in listOf(packetBytes) + documentChunks) {
            if (c.isEmpty()) continue
            output.write((c.size.toString(16) + "\r\n").toByteArray(Charsets.ISO_8859_1))
            output.write(c)
            output.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        output.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    @Test
    fun `print-job deletes the partially-spooled file when a chunked document exceeds the cap mid-stream`() {
        val q = JobQueue(FakeRenderingPipeline(), { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val caps = PrinterCapabilities.deskJet2300(URI.create("ipp://127.0.0.1:0/ipp/print"))
        val dir = createTempDir()
        spoolDir = dir
        // Generous enough that the small IPP header packet plus one document chunk both fit
        // comfortably, but a third chunk cannot — so streamToFile() has genuinely already
        // written earlier chunks to the spool file by the time the cap trips.
        val tinyLimitServer = LocalIppServer(port = 0, capabilities = caps, jobQueue = q, spoolDir = dir, maxDocumentBytes = 2000)
        tinyLimitServer.start(bindAddress = null)
        server = tinyLimitServer

        val packetBytes = ByteArrayOutputStream()
            .also { IppOutputStream(it).write(IppPacket(Operation.printJob, 80, operationGroup())) }
            .toByteArray()
        Socket("127.0.0.1", tinyLimitServer.actualPort).use { socket ->
            val out = socket.getOutputStream()
            val chunk = ByteArray(900) { 'A'.code.toByte() }
            writeChunkedIppRequest(out, packetBytes, listOf(chunk, chunk, chunk)) // 2700 doc bytes > 2000 cap
            val resp = readIppResponse(socket.getInputStream())
            assertEquals(Status.clientErrorRequestEntityTooLarge, resp.status)
        }

        val filesInSpoolDir = dir.listFiles()?.toList() ?: emptyList()
        assertEquals(
            "streamToFile must delete the partially-spooled file on failure, not leave a truncated document behind",
            0,
            filesInSpoolDir.size,
        )
    }

    @Test
    fun `send-document truncates the reserved spool file back to empty when a chunked document exceeds the cap mid-stream`() {
        val q = JobQueue(FakeRenderingPipeline(), { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val caps = PrinterCapabilities.deskJet2300(URI.create("ipp://127.0.0.1:0/ipp/print"))
        val dir = createTempDir()
        spoolDir = dir
        val tinyLimitServer = LocalIppServer(port = 0, capabilities = caps, jobQueue = q, spoolDir = dir, maxDocumentBytes = 2000)
        tinyLimitServer.start(bindAddress = null)
        server = tinyLimitServer

        val createResp = ipp(tinyLimitServer.actualPort, IppPacket(Operation.createJob, 81, operationGroup()))
        val jobId = createResp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        val job = queue!!.get(jobId)!!
        assertEquals(0L, job.spoolFile.length())

        val sendPacketBytes = ByteArrayOutputStream()
            .also {
                IppOutputStream(it).write(
                    IppPacket(
                        Operation.sendDocument, 82,
                        groupOf(
                            Tag.operationAttributes,
                            Types.attributesCharset.of("utf-8"),
                            Types.attributesNaturalLanguage.of("en"),
                            Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                            Types.jobId.of(jobId),
                        ),
                    ),
                )
            }
            .toByteArray()
        Socket("127.0.0.1", tinyLimitServer.actualPort).use { socket ->
            val out = socket.getOutputStream()
            val chunk = ByteArray(900) { 'B'.code.toByte() }
            writeChunkedIppRequest(out, sendPacketBytes, listOf(chunk, chunk, chunk)) // 2700 doc bytes > 2000 cap
            val resp = readIppResponse(socket.getInputStream())
            assertEquals(Status.clientErrorRequestEntityTooLarge, resp.status)
        }

        // The reserved spool file is not ours to delete — it must be restored to exactly the
        // (empty) length it had before this failed Send-Document call, not left holding
        // whichever chunks happened to be written before the cap tripped.
        assertTrue("reserved spool file must still exist after a failed append", job.spoolFile.exists())
        assertEquals(
            "reserved spool file must be truncated back to its pre-call length, not left partially written",
            0L,
            job.spoolFile.length(),
        )
        assertEquals(
            "the job must remain PENDING — a failed Send-Document must never enqueue it",
            dev.jaspreet.printserver.jobs.JobState.PENDING,
            job.state,
        )
    }
}
