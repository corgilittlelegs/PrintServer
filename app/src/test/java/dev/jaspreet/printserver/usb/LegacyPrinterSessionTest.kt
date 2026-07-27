package dev.jaspreet.printserver.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proves the actual bug Task 12 fixes: JobQueue's rendered-job writes and Raw9100Relay's
 * raw-client writes run on two different threads and, before LegacyPrinterSession existed,
 * both wrote straight to the shared transport with no coordination — their chunks could
 * genuinely interleave on the real USB bulk-OUT endpoint.
 */
class LegacyPrinterSessionTest {

    /** Records every write() call tagged with which writer made it, in observed order.
     *  Sleeps briefly inside write() to widen the race window — if the lock in
     *  [LegacyPrinterSession] didn't actually serialize callers, this delay gives a
     *  concurrent writer's chunk a real chance to land in between. */
    private class RecordingTransport : UsbTransport {
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        @Volatile var currentTag: String? = null

        override fun write(data: ByteArray, offset: Int, length: Int) {
            val tag = currentTag ?: "untagged"
            Thread.sleep(5)
            calls += tag to length
            Thread.sleep(5)
        }

        override fun read(buffer: ByteArray): Int = 0
        override fun close() {}
    }

    @Test
    fun `concurrent job and raw-client writes never interleave on the shared transport`() {
        val transport = RecordingTransport()
        val session = LegacyPrinterSession { transport }

        val startBoth = CountDownLatch(1)
        val jobDone = CountDownLatch(1)
        val rawDone = CountDownLatch(1)

        val jobThread = Thread {
            startBoth.await()
            session.writeExclusive("job") {
                transport.currentTag = "job"
                repeat(5) { i -> it.write(ByteArray(10), 0, 10 + i) }
            }
            jobDone.countDown()
        }
        val rawThread = Thread {
            startBoth.await()
            // Long timeout here — this test is about interleaving, not the reject policy
            // (covered separately below), so give the raw side plenty of time to acquire.
            session.tryWriteExclusive("raw", timeoutMs = 5_000) {
                transport.currentTag = "raw"
                repeat(5) { i -> it.write(ByteArray(10), 0, 20 + i) }
            }
            rawDone.countDown()
        }

        jobThread.start()
        rawThread.start()
        startBoth.countDown()
        assertTrue(jobDone.await(10, TimeUnit.SECONDS))
        assertTrue(rawDone.await(10, TimeUnit.SECONDS))

        val labels = transport.calls.map { it.first }
        assertEquals("both writers' chunks should have landed", 10, labels.size)
        // Exclusive access means one writer's whole 5-chunk run must complete before the
        // other's begins — i.e. at most one transition between "job" and "raw" in the
        // observed call order (all-job-then-all-raw, or the reverse).
        val transitions = (1 until labels.size).count { labels[it] != labels[it - 1] }
        assertTrue(
            "writers' chunks interleaved on the shared transport: $labels",
            transitions <= 1,
        )
    }

    @Test
    fun `a writer that cannot acquire the lock within its timeout is rejected, not run`() {
        val transport = RecordingTransport()
        val session = LegacyPrinterSession { transport }

        val holderEntered = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holderThread = Thread {
            session.writeExclusive("holder") {
                transport.currentTag = "holder"
                holderEntered.countDown()
                releaseHolder.await(5, TimeUnit.SECONDS)
            }
        }
        holderThread.start()
        assertTrue(holderEntered.await(5, TimeUnit.SECONDS))

        // The lock is held by "holder" — a short-timeout attempt must give up rather than
        // proceed and write concurrently.
        val invoked = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = session.tryWriteExclusive("raw-rejected", timeoutMs = 200) {
            invoked.set(true)
        }
        assertNull("tryWriteExclusive must return null when it can't acquire the lock", result)
        assertTrue("block must never run if the lock wasn't acquired", !invoked.get())

        releaseHolder.countDown()
        holderThread.join(5_000)

        // Once released, a fresh attempt succeeds.
        val result2 = session.tryWriteExclusive("raw-after-release", timeoutMs = 1_000) { "ok" }
        assertNotNull(result2)
        assertEquals("ok", result2)
    }

    @Test
    fun `writeExclusive waits for the lock instead of failing when it is briefly held`() {
        val transport = RecordingTransport()
        val session = LegacyPrinterSession { transport }

        val holderEntered = CountDownLatch(1)
        val holderThread = Thread {
            session.writeExclusive("raw-holder") {
                transport.currentTag = "raw-holder"
                holderEntered.countDown()
                Thread.sleep(300)
            }
        }
        holderThread.start()
        assertTrue(holderEntered.await(5, TimeUnit.SECONDS))

        // JobQueue's side (writeExclusive) has no timeout — it must still succeed once the
        // holder releases, rather than giving up like the raw-9100 side does.
        val start = System.currentTimeMillis()
        val result = session.writeExclusive("job-waiter") { "done" }
        val elapsed = System.currentTimeMillis() - start
        assertEquals("done", result)
        assertTrue("should have actually waited for the holder to release", elapsed >= 250)

        holderThread.join(5_000)
    }

    @Test
    fun `a raw-9100 caller already queued is served before a job that arrives later (fairness)`() {
        // Regression test for a bug where writeExclusive's old fast path called the no-arg
        // ReentrantLock.tryLock(), which is documented to ignore fairness and always "barge"
        // — letting a later-arriving job jump the queue ahead of a raw-9100 caller that was
        // already parked waiting. The lock is constructed fair specifically so that can't
        // happen; this proves it holds under an already-queued waiter.
        val transport = RecordingTransport()
        val session = LegacyPrinterSession { transport }
        val acquireOrder = Collections.synchronizedList(mutableListOf<String>())

        val holderEntered = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holderThread = Thread {
            session.writeExclusive("initial-holder") {
                holderEntered.countDown()
                releaseHolder.await(5, TimeUnit.SECONDS)
            }
        }
        holderThread.start()
        assertTrue(holderEntered.await(5, TimeUnit.SECONDS))

        // Park the raw-9100 caller behind the holder with a generous timeout, and wait until
        // it has genuinely enqueued on the lock (not just started its thread) before letting
        // the later job attempt to acquire — otherwise the ordering isn't actually guaranteed.
        val rawThread = Thread {
            session.tryWriteExclusive("raw-queued-first", timeoutMs = 10_000) {
                acquireOrder += "raw"
            }
        }
        rawThread.start()
        val queuedDeadline = System.currentTimeMillis() + 5_000
        while (!rawQueued(session) && System.currentTimeMillis() < queuedDeadline) {
            Thread.sleep(5)
        }
        assertTrue("raw thread should have enqueued on the lock", rawQueued(session))

        val jobThread = Thread {
            session.writeExclusive("job-arrives-later") {
                acquireOrder += "job"
            }
        }
        jobThread.start()
        // Give the job thread a moment to also reach lock.lock() and enqueue behind raw,
        // so both are genuinely contending for the lock before it's released.
        Thread.sleep(200)

        releaseHolder.countDown()
        holderThread.join(5_000)
        rawThread.join(5_000)
        jobThread.join(5_000)

        assertEquals(listOf("raw", "job"), acquireOrder)
    }

    private fun rawQueued(session: LegacyPrinterSession): Boolean = session.hasQueuedThreadsForTest()
}
