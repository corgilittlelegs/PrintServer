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

    /**
     * Regression test for a bug where [LegacyPrinterSession.writeExclusive]'s old fast path
     * called the no-arg [java.util.concurrent.locks.ReentrantLock.tryLock], which is
     * documented to ignore fairness and always "barge" — grabbing the lock immediately if it
     * happens to be free at that instant, even with another thread already parked waiting.
     *
     * A naive version of this test (hold the lock, park a raw caller behind it, start a job
     * thread, `Thread.sleep()` for a while, *then* release the holder) does NOT catch the bug:
     * by the time the job thread's `Thread.sleep()` has elapsed, the lock is still held, so
     * even the old buggy `tryLock()` fails its immediate attempt and falls through to the fair
     * `lock()` call — no barging opportunity exists because the job's very first acquisition
     * attempt always lands while the lock is genuinely still locked. The barging bug only
     * shows up in the narrow window between the holder's `unlock()` and the already-parked
     * raw caller actually resuming and reacquiring — both of which happen asynchronously on
     * separate threads, so hitting that window requires the job's attempt to start as close as
     * possible to the release moment, not measurably before or after it.
     *
     * To construct that race without relying on a lucky one-shot timing coincidence, this test
     * runs many trials. In each trial, a job thread is parked at a [java.util.concurrent.CyclicBarrier]
     * right before calling [LegacyPrinterSession.writeExclusive] — already running and ready to
     * go, not freshly spawned — so that releasing the barrier and releasing the holder's lock
     * happen back-to-back with minimal gap, giving the job's acquisition attempt a real chance
     * to race the raw caller's wake-up. This was verified empirically against the pre-fix
     * `tryLock()` fast path (see the Task 12 fairness-fix follow-up): run against the buggy
     * code, it does observe the job barging ahead within a bounded number of trials; run
     * against the fixed code, barging is structurally impossible (a fair lock's `lock()` call
     * never skips ahead of an already-queued waiter, regardless of timing) and the loop always
     * passes.
     */
    @Test
    fun `a raw-9100 caller already queued is never barged by a job racing the release moment`() {
        val maxTrials = 200
        // Multiple simultaneous job-attempt threads per trial: each is an independent chance
        // to land in the (narrow, sub-millisecond) window between the holder's unlock() and
        // the already-parked raw caller's wake-and-reacquire, which is what the old buggy
        // fast path could barge into.
        val jobAttemptsPerTrial = 6
        var trialsRun = 0
        var bargeObserved: List<String>? = null

        while (trialsRun < maxTrials && bargeObserved == null) {
            trialsRun++
            val transport = RecordingTransport()
            val session = LegacyPrinterSession { transport }
            val acquireOrder = Collections.synchronizedList(mutableListOf<String>())

            // holder and the K job-attempt threads all wait on the SAME barrier, released by
            // an independent (main-thread) party — not by each other — so none of them gets
            // a head-start from being the one that locally trips the barrier. This keeps the
            // holder's unlock() and each job's acquisition attempt on a level footing: all are
            // woken from a parked barrier.await() by the same release, at roughly the same time.
            val barrier = java.util.concurrent.CyclicBarrier(2 + jobAttemptsPerTrial)

            val holderEntered = CountDownLatch(1)
            val holderThread = Thread {
                session.writeExclusive("initial-holder") {
                    holderEntered.countDown()
                    barrier.await(2, TimeUnit.SECONDS)
                }
            }
            holderThread.start()
            assertTrue(holderEntered.await(2, TimeUnit.SECONDS))

            // Park the raw-9100 caller behind the holder, and wait until it has genuinely
            // enqueued on the lock (parked in the AQS wait queue, not just running its thread's
            // startup code) before racing the job attempts against the release.
            val rawThread = Thread {
                session.tryWriteExclusive("raw-queued-first", timeoutMs = 2_000) {
                    acquireOrder += "raw"
                }
            }
            rawThread.start()
            val queuedDeadline = System.currentTimeMillis() + 2_000
            while (!session.hasQueuedThreadsForTest() && System.currentTimeMillis() < queuedDeadline) {
                Thread.onSpinWait()
            }
            assertTrue("raw thread should have enqueued on the lock", session.hasQueuedThreadsForTest())

            val jobDone = CountDownLatch(jobAttemptsPerTrial)
            val jobThreads = (1..jobAttemptsPerTrial).map { idx ->
                Thread {
                    barrier.await(2, TimeUnit.SECONDS)
                    session.writeExclusive("job-races-release-$idx") {
                        acquireOrder += "job"
                    }
                    jobDone.countDown()
                }
            }
            jobThreads.forEach { it.start() }
            // Main thread is the final party: it does no other work first, so the K job
            // threads and the holder are the ones actually parked waiting on the barrier when
            // it trips, rather than main itself (which has no stake in the race).
            barrier.await(2, TimeUnit.SECONDS)

            assertTrue(jobDone.await(2, TimeUnit.SECONDS))
            holderThread.join(2_000)
            rawThread.join(2_000)

            // A barge is any "job" entry landing before "raw" in acquisition order — or "raw"
            // never showing up at all despite its generous 2s timeout, which would mean a job
            // starved it out entirely.
            val jobIndex = acquireOrder.indexOf("job")
            val rawIndex = acquireOrder.indexOf("raw")
            if (jobIndex >= 0 && (rawIndex < 0 || jobIndex < rawIndex)) {
                bargeObserved = acquireOrder.toList()
            }
        }

        assertNull(
            "job barged ahead of an already-queued raw caller on trial $trialsRun/$maxTrials " +
                "(order: $bargeObserved) — the fair lock must never let a later attempt skip an " +
                "already-waiting caller",
            bargeObserved,
        )
    }
}
