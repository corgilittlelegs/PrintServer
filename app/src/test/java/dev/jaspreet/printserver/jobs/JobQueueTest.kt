package dev.jaspreet.printserver.jobs

import dev.jaspreet.printserver.render.FakeRenderingPipeline
import dev.jaspreet.printserver.usb.FakePrinterTransport
import dev.jaspreet.printserver.usb.LegacyPrinterSession
import dev.jaspreet.printserver.usb.UsbTransport
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
import java.util.concurrent.atomic.AtomicBoolean

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
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), LegacyPrinterSession { printer }) { done.countDown() }
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
            LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } },
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
        val q = JobQueue(blockingPipeline, LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } }) {}
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
        val q = JobQueue(FakeRenderingPipeline(), LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        assertNull(q.get(999))
    }

    @Test
    fun `onJobStateChanged fires PENDING then PROCESSING then COMPLETED, in order`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val states = java.util.Collections.synchronizedList(mutableListOf<JobState>())
        val q = JobQueue(
            FakeRenderingPipeline("PCL!".toByteArray()), LegacyPrinterSession { printer },
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
        val q = JobQueue(FakeRenderingPipeline(), LegacyPrinterSession { printer }) { done.countDown() }
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
            blockingPipeline, LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } },
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
            blockingPipeline, LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } },
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
    fun `a job submitted after a timeout poisons the queue is failed without invoking the pipeline again`() {
        // Reproduces the scenario Task 11 asks us to verify: once a render times out, the
        // original render call is left running forever on renderExecutor's thread (it can't be
        // safely killed) and the queue is poisoned. A job submitted afterward must be diverted
        // by the worker loop's `if (poisoned)` check *before* it ever reaches process() —
        // pipeline.render() must not be invoked a second time while the first is still hung.
        val invocations = java.util.concurrent.atomic.AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(
                document: File, output: File, format: String,
                quality: PrintQuality, colorMode: ColorMode,
            ) {
                invocations.incrementAndGet()
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS) // never reached by the second call
                output.writeBytes("X".toByteArray())
            }
        }
        val stuck = CountDownLatch(1)
        val q = JobQueue(
            pipeline, LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } },
            renderTimeoutMs = 100,
            onPipelineStuck = { stuck.countDown() },
        )
        queue = q

        val firstId = q.submit(pdf(), "job-timeout")
        assertTrue("onPipelineStuck should fire once the render exceeds renderTimeoutMs", stuck.await(5, TimeUnit.SECONDS))
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5000
        while (q.get(firstId)!!.state != JobState.ABORTED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(JobState.ABORTED, q.get(firstId)!!.state)
        assertEquals("render-timeout", q.get(firstId)!!.stateReason)

        val secondId = q.submit(pdf(), "job-after-poison")
        val deadline2 = System.currentTimeMillis() + 5000
        while (q.get(secondId)!!.state != JobState.ABORTED && System.currentTimeMillis() < deadline2) {
            Thread.sleep(10)
        }
        assertEquals(JobState.ABORTED, q.get(secondId)!!.state)
        assertEquals("queue-unavailable", q.get(secondId)!!.stateReason)
        assertEquals("pipeline.render() must not run a second time", 1, invocations.get())

        releaseFirst.countDown() // let the original hung render finish so its thread doesn't leak past the test
    }

    @Test
    fun `retrying a job after a timeout poisons the queue is failed without invoking the pipeline again`() {
        // Same scenario as above, but specifically through JobQueue.retry() — retry()'s
        // submit(copy, ...) doesn't check `poisoned` itself; only the worker loop does, right
        // before process() is called. This confirms a retried job is still caught there.
        val invocations = java.util.concurrent.atomic.AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(
                document: File, output: File, format: String,
                quality: PrintQuality, colorMode: ColorMode,
            ) {
                invocations.incrementAndGet()
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                output.writeBytes("X".toByteArray())
            }
        }
        val stuck = CountDownLatch(1)
        val q = JobQueue(
            pipeline, LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } },
            renderTimeoutMs = 100,
            onPipelineStuck = { stuck.countDown() },
        )
        queue = q

        val firstId = q.submit(pdf(), "job-timeout")
        assertTrue(stuck.await(5, TimeUnit.SECONDS))
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5000
        while (q.get(firstId)!!.state != JobState.ABORTED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(JobState.ABORTED, q.get(firstId)!!.state)
        assertTrue("retry() requires the spool file to still exist", q.get(firstId)!!.spoolFile.exists())

        val retryId = q.retry(firstId)
        assertNotNull("retry should still register a new job even though the queue is poisoned", retryId)
        val deadline2 = System.currentTimeMillis() + 5000
        while (q.get(retryId!!)!!.state != JobState.ABORTED && System.currentTimeMillis() < deadline2) {
            Thread.sleep(10)
        }
        assertEquals(JobState.ABORTED, q.get(retryId)!!.state)
        assertEquals("queue-unavailable", q.get(retryId)!!.stateReason)
        assertEquals("pipeline.render() must not run a second time for the retried job", 1, invocations.get())

        releaseFirst.countDown()
    }

    @Test
    fun `evicting a retained ABORTED job deletes its spool file`() {
        // MAX_RETAINED_JOBS is 200 — submit 201 jobs that all fail, forcing eviction of the oldest.
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val finishedAll = CountDownLatch(201)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = IOException("bad pdf")),
            LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } },
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
        val q = JobQueue(pipeline, LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } }) { done.countDown() }
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
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), LegacyPrinterSession { printer }) { done.countDown() }
        queue = q
        val id = q.submit(pdf(), "ok-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.COMPLETED, q.get(id)!!.state)
        assertNull(q.retry(id))
    }

    @Test
    fun `retry on an unknown job id returns null`() {
        val q = JobQueue(FakeRenderingPipeline(), LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        assertNull(q.retry(999))
    }

    @Test
    fun `retry after the spool file was evicted returns null`() {
        val done = java.util.concurrent.CountDownLatch(201)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = IOException("bad pdf")),
            LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } },
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
        val q = JobQueue(blockingPipeline, LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } }) {}
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
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), LegacyPrinterSession { printer }) { done.countDown() }
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
        val q = JobQueue(pipeline, LegacyPrinterSession { printer }) { done.countDown() }
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
        val q = JobQueue(pipeline, LegacyPrinterSession { printer }) { done.countDown() }
        queue = q
        q.submit(pdf(), "test-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(PrintQuality.NORMAL), pipeline.qualities)
        assertEquals(listOf(ColorMode.COLOR), pipeline.colorModes)
    }

    @Test
    fun `spooledBytes reflects the final document size and survives spool file deletion on completion`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val spool = pdf()
        val expectedSize = spool.length()
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), LegacyPrinterSession { printer }) { done.countDown() }
        queue = q
        val id = q.submit(spool, "test-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val job = q.get(id)!!
        assertEquals(JobState.COMPLETED, job.state)
        // COMPLETED jobs have their spool file deleted — spooledBytes must not depend on it.
        assertFalse("spool file should be deleted once the job completes", spool.exists())
        assertEquals(expectedSize, job.spooledBytes)
        assertTrue(expectedSize > 0)
    }

    @Test
    fun `spooledBytes reflects the final document size for a render failure (ABORTED, file retained)`() {
        val spool = pdf()
        val expectedSize = spool.length()
        val done = CountDownLatch(1)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = IOException("bad pdf")),
            LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } },
        ) { done.countDown() }
        queue = q
        val id = q.submit(spool, "broken-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val job = q.get(id)!!
        assertEquals(JobState.ABORTED, job.state)
        assertEquals(expectedSize, job.spooledBytes)
    }

    @Test
    fun `spooledBytes is 0 while reserved and set only once enqueue() delivers the document`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), LegacyPrinterSession { printer }) { done.countDown() }
        queue = q
        val spool = File.createTempFile("job", ".pdf").also { tempFiles += it } // empty, as reserve() expects
        val id = q.reserve(spool, "two-phase-doc")
        assertEquals(0L, q.get(id)!!.spooledBytes)

        // Simulate Send-Document writing the document body onto the reserved spool file.
        assertNotNull(q.beginSpooling(id))
        assertEquals(JobState.SPOOLING, q.get(id)!!.state)
        spool.writeText("%PDF-two-phase-doc")
        val expectedSize = spool.length()
        assertTrue(q.enqueue(id))
        assertEquals(expectedSize, q.get(id)!!.spooledBytes)

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.COMPLETED, q.get(id)!!.state)
        assertEquals(
            "spooledBytes must survive past the spool file's deletion on completion",
            expectedSize,
            q.get(id)!!.spooledBytes,
        )
    }

    @Test
    fun `beginSpooling claims a reserved job exactly once before enqueue`() {
        val q = JobQueue(FakeRenderingPipeline(), LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val spool = File.createTempFile("job", ".pdf").also { tempFiles += it }
        val id = q.reserve(spool, "two-phase-doc")

        val claimed = q.beginSpooling(id)

        assertNotNull(claimed)
        assertEquals(JobState.SPOOLING, q.get(id)!!.state)
        assertNull("a duplicate Send-Document must not claim the same spool file", q.beginSpooling(id))
        assertFalse("enqueue should fail for an unknown job", q.enqueue(999))
    }

    @Test
    fun `enqueue is rejected until a reserved job has been claimed for spooling`() {
        val q = JobQueue(FakeRenderingPipeline(), LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val spool = File.createTempFile("job", ".pdf").also { tempFiles += it }
        val id = q.reserve(spool, "two-phase-doc")

        spool.writeText("%PDF-two-phase-doc")

        assertFalse(q.enqueue(id))
        assertEquals(JobState.PENDING, q.get(id)!!.state)
        assertEquals(0L, q.get(id)!!.spooledBytes)
    }

    @Test
    fun `fail transitions a PENDING job to ABORTED with the given reason and fires callbacks, without enqueueing it`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val stateChanges = mutableListOf<JobState>()
        val finished = CountDownLatch(1)
        val q = JobQueue(
            FakeRenderingPipeline("PCL!".toByteArray()),
            LegacyPrinterSession { printer },
            onJobStateChanged = { stateChanges += it.state },
            onJobFinished = { finished.countDown() },
        )
        queue = q
        val spool = File.createTempFile("job", ".pdf").also { tempFiles += it } // empty, as reserve() expects
        val id = q.reserve(spool, "two-phase-doc")

        assertTrue(q.fail(id, "request-entity-too-large"))

        val job = q.get(id)!!
        assertEquals(JobState.ABORTED, job.state)
        assertEquals("request-entity-too-large", job.stateReason)
        assertTrue("onJobFinished must fire for a fail()-ed job", finished.await(5, TimeUnit.SECONDS))
        assertTrue(stateChanges.contains(JobState.ABORTED))
        // Never handed to the worker — pipeline.render() must not run for a failed job.
        assertFalse(q.listActive().contains(job))
    }

    @Test
    fun `fail transitions a SPOOLING job to ABORTED with the given reason`() {
        val finished = CountDownLatch(1)
        val q = JobQueue(
            FakeRenderingPipeline("PCL!".toByteArray()),
            LegacyPrinterSession { FakePrinterTransport { ByteArray(0) } },
            onJobFinished = { finished.countDown() },
        )
        queue = q
        val spool = File.createTempFile("job", ".pdf").also { tempFiles += it }
        val id = q.reserve(spool, "two-phase-doc")
        assertNotNull(q.beginSpooling(id))

        assertTrue(q.fail(id, "request-entity-too-large"))

        val job = q.get(id)!!
        assertEquals(JobState.ABORTED, job.state)
        assertEquals("request-entity-too-large", job.stateReason)
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(q.listActive().contains(job))
    }

    @Test
    fun `fail is a no-op for a job that already left PENDING`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), LegacyPrinterSession { printer }) { done.countDown() }
        queue = q
        val id = q.submit(pdf(), "test-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.COMPLETED, q.get(id)!!.state)

        assertFalse(q.fail(id, "request-entity-too-large"))
        assertEquals(JobState.COMPLETED, q.get(id)!!.state)
    }

    @Test
    fun `fail on an unknown id returns false`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val q = JobQueue(FakeRenderingPipeline(), LegacyPrinterSession { printer })
        queue = q
        assertFalse(q.fail(999, "request-entity-too-large"))
    }

    @Test
    fun `shutdown waits for an in-flight write to finish before returning`() {
        val writeStarted = CountDownLatch(1)
        val released = AtomicBoolean(false)
        // A real USB bulk write blocks in native I/O and does not unblock on
        // Thread.interrupt() -- unlike CountDownLatch.await(), which is interruptible and
        // would let shutdown()'s worker.interrupt() cut the "write" short, defeating the
        // point of this test. Poll a plain volatile instead so an interrupt landing here
        // behaves like it would against the real transport.
        val transport = object : UsbTransport {
            override fun write(data: ByteArray, offset: Int, length: Int) {
                writeStarted.countDown()
                while (!released.get()) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { /* not abandoned mid-write */ }
                }
            }
            override fun read(buffer: ByteArray): Int = 0
            override fun close() {}
        }
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), LegacyPrinterSession { transport })
        queue = q
        q.submit(pdf(), "test-doc")
        assertTrue("write should have started", writeStarted.await(5, TimeUnit.SECONDS))

        val shutdownReturned = AtomicBoolean(false)
        val shutdownThread = Thread { q.shutdown(); shutdownReturned.set(true) }
        shutdownThread.start()
        Thread.sleep(200)
        assertFalse("shutdown must not return while a write is still in flight", shutdownReturned.get())

        released.set(true)
        shutdownThread.join(5_000)
        assertTrue("shutdown should have returned once the write finished", shutdownReturned.get())
    }
}
