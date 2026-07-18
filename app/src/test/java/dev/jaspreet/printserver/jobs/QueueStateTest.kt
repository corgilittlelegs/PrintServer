package dev.jaspreet.printserver.jobs

import dev.jaspreet.printserver.render.FakeRenderingPipeline
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QueueStateTest {

    private var queue: JobQueue? = null
    private val tempFiles = mutableListOf<File>()

    private fun pdf(): File = File.createTempFile("job", ".pdf").also {
        it.writeText("%PDF-fake")
        tempFiles += it
    }

    @After
    fun tearDown() {
        QueueState.detach()
        queue?.shutdown()
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun `refresh with nothing attached leaves entries empty`() {
        QueueState.refresh()
        assertTrue(QueueState.entries.value.isEmpty())
    }

    @Test
    fun `refresh reflects the attached queue's active jobs`() {
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
        QueueState.attach(q)

        val first = q.submit(pdf(), "job-a")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = q.submit(pdf(), "job-b")
        QueueState.refresh()

        val entries = QueueState.entries.value.associateBy { it.id }
        assertEquals(2, entries.size)
        assertEquals(JobState.PROCESSING, entries.getValue(first).state)
        assertNull(entries.getValue(first).position)
        assertEquals(JobState.PENDING, entries.getValue(second).state)
        assertEquals(1, entries.getValue(second).position)

        release.countDown()
    }

    @Test
    fun `detach clears entries and future actions`() {
        val q = JobQueue(FakeRenderingPipeline(), { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        QueueState.attach(q)
        QueueState.detach()
        assertTrue(QueueState.entries.value.isEmpty())
        assertEquals(false, QueueState.cancel(1))
        assertNull(QueueState.retry(1))
    }

    @Test
    fun `cancel and retry delegate to the attached queue`() {
        val spool = pdf()
        val done = CountDownLatch(1)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = java.io.IOException("bad pdf")),
            { FakePrinterTransport { ByteArray(0) } },
        ) { done.countDown() }
        queue = q
        QueueState.attach(q)
        val id = q.submit(spool, "broken-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))

        val newId = QueueState.retry(id)
        assertNotNull(newId)
        assertEquals(false, QueueState.cancel(999)) // unknown id
    }
}
