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
}
