package dev.jaspreet.printserver.usb

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes every write to the single legacy-printer USB transport shared by
 * Tier 2's rendered print jobs ([dev.jaspreet.printserver.jobs.JobQueue]) and the raw
 * port-9100 relay ([dev.jaspreet.printserver.relay.Raw9100Relay]). Those two run on
 * separate threads and, before this class existed, both called the shared
 * transport-provider and wrote straight to it — their 64KB write() chunks could
 * genuinely interleave on the real USB bulk-OUT endpoint, corrupting both streams,
 * since the printer has no framing to tell two logical byte-streams apart.
 *
 * Policy (see [writeExclusive] and [tryWriteExclusive]):
 * - A Tier 2 print job (JobQueue) waits indefinitely for the lock. JobQueue's single
 *   worker thread already serializes jobs against each other, print jobs are already
 *   expected to take a while, and there's no independent "job timeout" concern beyond
 *   render time (see [dev.jaspreet.printserver.jobs.JobQueue]'s renderTimeoutMs, which
 *   covers rendering, not writing) — so blocking briefly behind a raw-9100 client is
 *   acceptable and simply delays completion rather than corrupting output.
 * - A raw-9100 client is a foreground, interactive TCP connection with no queue/retry
 *   semantics of its own. It waits only briefly (a few seconds — long enough to let an
 *   in-flight small write finish, short enough not to hang the client indefinitely
 *   behind a multi-MB print job) and is rejected (its connection closed) if the lock
 *   still isn't free, rather than either hanging forever or being allowed to write
 *   concurrently and corrupt the stream.
 */
class LegacyPrinterSession(
    private val transportProvider: () -> UsbTransport,
) {
    // Fair so a raw-9100 client isn't starved indefinitely by a stream of back-to-back
    // print jobs re-acquiring the lock ahead of it every time it's released.
    private val lock = ReentrantLock(true)

    /**
     * Acquires the write lock, waiting indefinitely, then runs [block] with the shared
     * transport and releases afterward. Intended for JobQueue's rendered-job writes,
     * where blocking briefly behind another writer is acceptable (see class doc).
     */
    fun <T> writeExclusive(ownerLabel: String, block: (UsbTransport) -> T): T {
        // hasQueuedThreads() is a read-only check — unlike tryLock(), it never itself
        // attempts to acquire the lock, so it can't barge ahead of an already-queued
        // waiter. (The no-arg ReentrantLock.tryLock() is documented to ignore fairness
        // and always barge; using it here as a "fast path" would let a stream of
        // back-to-back jobs repeatedly cut in front of an already-waiting raw-9100
        // client, defeating the fair lock below.)
        if (lock.hasQueuedThreads()) {
            Log.i(TAG, "$ownerLabel: legacy printer transport busy — waiting for it to free up")
        }
        // Plain lock() (not lockInterruptibly()): a job's write must not be abandoned
        // mid-stream by an interrupt — JobQueue.shutdown() interrupts the worker thread,
        // but a write already in flight should still finish rather than leave the
        // transport in a half-written state.
        lock.lock()
        try {
            return block(transportProvider())
        } finally {
            lock.unlock()
        }
    }

    /**
     * Attempts to acquire the write lock within [timeoutMs], then runs [block] with the
     * shared transport and releases afterward. Returns null — without invoking [block] —
     * if the lock could not be acquired in time; callers MUST treat that as a conflict
     * (e.g. close the connection), never proceed without the lock. Intended for
     * Raw9100Relay, which has no queueing/retry of its own (see class doc).
     */
    fun <T> tryWriteExclusive(ownerLabel: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: (UsbTransport) -> T): T? {
        val acquired = try {
            lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            Log.w(
                TAG,
                "$ownerLabel: legacy printer transport busy (write lock held by another " +
                    "writer) — could not acquire it within ${timeoutMs}ms, rejecting",
            )
            return null
        }
        try {
            return block(transportProvider())
        } finally {
            lock.unlock()
        }
    }

    /** Read-only, non-acquiring peek at whether another thread is already queued for the
     *  lock. Exposed at internal visibility purely so tests can synchronize on "the other
     *  caller has genuinely enqueued" before asserting fairness ordering — production code
     *  has no need for it. */
    internal fun hasQueuedThreadsForTest(): Boolean = lock.hasQueuedThreads()

    companion object {
        private const val TAG = "LegacyPrinterSession"

        // A raw-9100 client waits this long for an in-flight write (typically a job
        // already streaming) to release the lock before its connection is rejected.
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
