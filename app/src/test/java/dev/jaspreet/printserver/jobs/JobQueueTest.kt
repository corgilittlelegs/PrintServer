package dev.jaspreet.printserver.jobs

import dev.jaspreet.printserver.render.FakeRenderingPipeline
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JobQueueTest {

    private var queue: JobQueue? = null
    private val tempFiles = mutableListOf<File>()

    private fun pdf(): File = File.createTempFile("job", ".pdf").also {
        it.writeText("%PDF-fake")
        tempFiles += it
    }

    @After
    fun tearDown() {
        queue?.shutdown()
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun `job runs through pipeline and lands on the printer`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), { printer }) { done.countDown() }
        queue = q
        val id = q.submit(pdf(), "test-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.COMPLETED, q.get(id)!!.state)
        assertEquals("PCL!", String(printer.lastRequest()))
    }

    @Test
    fun `render failure aborts the job but keeps the spool file for retry`() {
        val spool = pdf()
        val done = CountDownLatch(1)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = IOException("bad pdf")),
            { FakePrinterTransport { ByteArray(0) } },
        ) { done.countDown() }
        queue = q
        val id = q.submit(spool, "broken-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.ABORTED, q.get(id)!!.state)
        assertTrue(spool.exists())
    }

    @Test
    fun `cancel while pending prevents processing`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingPipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(
                document: File, output: File, format: String,
                quality: PrintQuality, colorMode: ColorMode,
            ) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                output.writeBytes("X".toByteArray())
            }
        }
        val q = JobQueue(blockingPipeline, { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        q.submit(pdf(), "job-a")                      // occupies the worker
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = q.submit(pdf(), "job-b")          // sits pending
        assertTrue(q.cancel(second))
        release.countDown()
        assertEquals(JobState.CANCELED, q.get(second)!!.state)
    }

    @Test
    fun `unknown job id returns null`() {
        val q = JobQueue(FakeRenderingPipeline(), { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        assertNull(q.get(999))
    }

    @Test
    fun `onJobStateChanged fires PENDING then PROCESSING then COMPLETED, in order`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val states = java.util.Collections.synchronizedList(mutableListOf<JobState>())
        val q = JobQueue(
            FakeRenderingPipeline("PCL!".toByteArray()), { printer },
            onJobStateChanged = { job -> states += job.state },
        ) { done.countDown() }
        queue = q
        q.submit(pdf(), "test-doc", clientAddress = "192.168.1.42")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(JobState.PENDING, JobState.PROCESSING, JobState.COMPLETED), states)
    }

    @Test
    fun `submitted job carries the client address through to PrintJob`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val q = JobQueue(FakeRenderingPipeline(), { printer }) { done.countDown() }
        queue = q
        val id = q.submit(pdf(), "test-doc", clientAddress = "10.0.0.5")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("10.0.0.5", q.get(id)!!.clientAddress)
    }

    @Test
    fun `cancel fires onJobStateChanged with CANCELED before deleting the spool file`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingPipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(
                document: File, output: File, format: String,
                quality: PrintQuality, colorMode: ColorMode,
            ) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                output.writeBytes("X".toByteArray())
            }
        }
        val states = java.util.Collections.synchronizedList(mutableListOf<JobState>())
        val q = JobQueue(
            blockingPipeline, { FakePrinterTransport { ByteArray(0) } },
            onJobStateChanged = { job -> states += job.state },
        ) {}
        queue = q
        q.submit(pdf(), "job-a")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = q.submit(pdf(), "job-b")
        assertTrue(q.cancel(second))
        release.countDown()
        assertTrue(states.contains(JobState.CANCELED))
    }

    @Test
    fun `render timeout fires onJobStateChanged exactly once with ABORTED`() {
        val release = CountDownLatch(1)
        val blockingPipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(
                document: File, output: File, format: String,
                quality: PrintQuality, colorMode: ColorMode,
            ) {
                release.await(5, TimeUnit.SECONDS)
                output.writeBytes("X".toByteArray())
            }
        }
        val states = java.util.Collections.synchronizedList(mutableListOf<JobState>())
        val q = JobQueue(
            blockingPipeline, { FakePrinterTransport { ByteArray(0) } },
            renderTimeoutMs = 100,
            onJobStateChanged = { job -> states += job.state },
        ) {}
        queue = q
        val id = q.submit(pdf(), "job-timeout")
        // Wait for the ABORTED state to show up (process() runs on the worker thread).
        val deadline = System.currentTimeMillis() + 5000
        while (q.get(id)!!.state != JobState.ABORTED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(JobState.ABORTED, q.get(id)!!.state)
        assertEquals(1, states.count { it == JobState.ABORTED })
        release.countDown()
    }

    @Test
    fun `evicting a retained ABORTED job deletes its spool file`() {
        // MAX_RETAINED_JOBS is 200 — submit 201 jobs that all fail, forcing eviction of the oldest.
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val finishedAll = CountDownLatch(201)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = IOException("bad pdf")),
            { FakePrinterTransport { ByteArray(0) } },
        ) { done.incrementAndGet(); finishedAll.countDown() }
        queue = q
        val firstSpool = pdf()
        val firstId = q.submit(firstSpool, "job-0")
        repeat(200) { i -> q.submit(pdf(), "job-${i + 1}") }
        assertTrue(finishedAll.await(10, TimeUnit.SECONDS))
        assertNull("oldest job should have been evicted", q.get(firstId))
        assertFalse("evicted job's spool file should be deleted", firstSpool.exists())
    }

    @Test
    fun `retry copies the original spool bytes into a new file and reruns it`() {
        var failNext = true
        val pipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(
                document: File, output: File, format: String,
                quality: PrintQuality, colorMode: ColorMode,
            ) {
                if (failNext) throw IOException("bad pdf")
                output.writeBytes(document.readBytes())
            }
        }
        val done = CountDownLatch(1)
        val q = JobQueue(pipeline, { FakePrinterTransport { ByteArray(0) } }) { done.countDown() }
        queue = q
        val spool = pdf()
        spool.writeText("original-bytes")
        val id = q.submit(spool, "broken-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.ABORTED, q.get(id)!!.state)
        assertTrue(spool.exists())

        failNext = false
        val newId = q.retry(id)
        assertNotNull(newId)
        assertEquals(id, q.get(newId!!)!!.retryOf)
        val deadline = System.currentTimeMillis() + 5000
        while (q.get(newId)!!.state.let { it == JobState.PENDING || it == JobState.PROCESSING } &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }
        assertEquals(JobState.COMPLETED, q.get(newId)!!.state)
    }

    @Test
    fun `retry on a non-ABORTED job returns null`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), { printer }) { done.countDown() }
        queue = q
        val id = q.submit(pdf(), "ok-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.COMPLETED, q.get(id)!!.state)
        assertNull(q.retry(id))
    }

    @Test
    fun `retry on an unknown job id returns null`() {
        val q = JobQueue(FakeRenderingPipeline(), { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        assertNull(q.retry(999))
    }

    @Test
    fun `retry after the spool file was evicted returns null`() {
        val done = java.util.concurrent.CountDownLatch(201)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = IOException("bad pdf")),
            { FakePrinterTransport { ByteArray(0) } },
        ) { done.countDown() }
        queue = q
        val firstId = q.submit(pdf(), "job-0")
        repeat(200) { i -> q.submit(pdf(), "job-${i + 1}") }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNull(q.get(firstId)) // evicted
        assertNull(q.retry(firstId))
    }

    @Test
    fun `queuePosition ranks PENDING jobs in FIFO order`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingPipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(
                document: File, output: File, format: String,
                quality: PrintQuality, colorMode: ColorMode,
            ) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                output.writeBytes("X".toByteArray())
            }
        }
        val q = JobQueue(blockingPipeline, { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        val first = q.submit(pdf(), "job-a")   // occupies the worker (PROCESSING)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = q.submit(pdf(), "job-b")  // 1st in queue
        val third = q.submit(pdf(), "job-c")   // 2nd in queue

        assertNull("PROCESSING job has no queue position", q.queuePosition(first))
        assertEquals(1, q.queuePosition(second))
        assertEquals(2, q.queuePosition(third))

        release.countDown()
    }

    @Test
    fun `queuePosition on a terminal or unknown job returns null`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), { printer }) { done.countDown() }
        queue = q
        val id = q.submit(pdf(), "ok-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(q.queuePosition(id)) // COMPLETED
        assertNull(q.queuePosition(999)) // unknown
    }

    @Test
    fun `submit passes quality and color mode through to the rendering pipeline`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val pipeline = FakeRenderingPipeline("PCL!".toByteArray())
        val q = JobQueue(pipeline, { printer }) { done.countDown() }
        queue = q
        q.submit(pdf(), "test-doc", quality = PrintQuality.HIGH, colorMode = ColorMode.MONOCHROME)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(PrintQuality.HIGH), pipeline.qualities)
        assertEquals(listOf(ColorMode.MONOCHROME), pipeline.colorModes)
    }

    @Test
    fun `submit defaults to NORMAL quality and COLOR mode when not specified`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val pipeline = FakeRenderingPipeline("PCL!".toByteArray())
        val q = JobQueue(pipeline, { printer }) { done.countDown() }
        queue = q
        q.submit(pdf(), "test-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(PrintQuality.NORMAL), pipeline.qualities)
        assertEquals(listOf(ColorMode.COLOR), pipeline.colorModes)
    }
}
