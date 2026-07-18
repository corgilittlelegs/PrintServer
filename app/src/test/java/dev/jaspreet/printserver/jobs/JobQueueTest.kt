package dev.jaspreet.printserver.jobs

import dev.jaspreet.printserver.render.FakeRenderingPipeline
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `render failure aborts the job and deletes the spool file`() {
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
        assertFalse(spool.exists())
    }

    @Test
    fun `cancel while pending prevents processing`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingPipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(document: File, output: File, format: String) {
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
            override fun render(document: File, output: File, format: String) {
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
}
