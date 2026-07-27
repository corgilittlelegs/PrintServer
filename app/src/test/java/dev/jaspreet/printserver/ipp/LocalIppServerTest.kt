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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class LocalIppServerTest {

    private var server: LocalIppServer? = null
    private var queue: JobQueue? = null

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
        val s = LocalIppServer(
            port = 0,
            capabilities = capabilities,
            jobQueue = q,
            spoolDir = createTempDir(),
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
}
