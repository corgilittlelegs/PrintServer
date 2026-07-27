package dev.jaspreet.printserver.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises [NativeCallGuard] directly — deliberately not through [HpcupsNative], since that
 * object's `init { System.loadLibrary(...) }` would throw `UnsatisfiedLinkError` in a plain
 * JVM unit test. [NativeCallGuard] is a standalone class for exactly this reason.
 */
class NativeCallGuardTest {

    @Test
    fun `a second call while the first is still running throws immediately`() {
        val guard = NativeCallGuard("test")
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstDone = CountDownLatch(1)

        val first = Thread {
            guard.guarded {
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
            firstDone.countDown()
        }
        first.start()
        assertTrue("first call should have entered the guarded region", firstEntered.await(5, TimeUnit.SECONDS))

        val secondError = AtomicReference<Throwable?>()
        val second = Thread {
            try {
                guard.guarded { fail("second call's block must never run while the first is in progress") }
            } catch (e: Throwable) {
                secondError.set(e)
            }
        }
        second.start()
        second.join(5000)

        assertTrue(
            "overlapping call should throw IllegalStateException",
            secondError.get() is IllegalStateException,
        )

        releaseFirst.countDown()
        assertTrue(firstDone.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `guard is reusable once a call completes normally`() {
        val guard = NativeCallGuard("test")
        assertEquals("ok", guard.guarded { "ok" })
        // A second, non-overlapping call must succeed — the busy flag was cleared.
        assertEquals("ok-again", guard.guarded { "ok-again" })
    }

    @Test
    fun `busy flag is cleared even when the block throws`() {
        val guard = NativeCallGuard("test")
        try {
            guard.guarded { throw RuntimeException("boom") }
            fail("expected the block's exception to propagate")
        } catch (e: RuntimeException) {
            assertEquals("boom", e.message)
        }
        // If the flag hadn't been cleared in finally, this would throw IllegalStateException
        // instead of running the block.
        assertEquals("recovered", guard.guarded { "recovered" })
    }
}
