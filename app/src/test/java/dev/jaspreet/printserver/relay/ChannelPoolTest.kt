package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.FakePrinterTransport
import dev.jaspreet.printserver.usb.UsbTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ChannelPoolTest {

    private fun fake() = FakePrinterTransport { ByteArray(0) }

    @Test
    fun `lease returns released channel`() {
        val a = fake()
        val pool = ChannelPool(listOf(a))
        val leased = pool.lease(1000)
        assertSame(a, leased)
        pool.release(leased)
        assertSame(a, pool.lease(1000))
    }

    @Test(expected = IOException::class)
    fun `lease times out when all channels busy`() {
        val pool = ChannelPool(listOf(fake()))
        pool.lease(100)
        pool.lease(100) // no release -> must throw
    }

    @Test
    fun `discard closes channel and signals when none left`() {
        val a = fake()
        val dead = AtomicBoolean(false)
        val pool = ChannelPool(listOf(a))
        pool.onAllChannelsDead = { dead.set(true) }
        pool.discard(pool.lease(1000))
        assertTrue(a.closed)
        assertTrue(dead.get())
    }

    @Test
    fun `multi-channel leasing exhausts and recovers`() {
        val a = fake()
        val b = fake()
        val pool = ChannelPool(listOf(a, b))

        val first = pool.lease(1000)
        val second = pool.lease(1000)
        assertTrue((first === a && second === b) || (first === b && second === a))

        try {
            pool.lease(100)
            org.junit.Assert.fail("expected IOException: no channels available")
        } catch (_: IOException) {
            // expected
        }

        pool.release(first)
        val third = pool.lease(1000)
        assertSame(first, third)
    }

    @Test
    fun `partial discard does not fire onAllChannelsDead`() {
        val a = fake()
        val b = fake()
        val dead = AtomicBoolean(false)
        val pool = ChannelPool(listOf(a, b))
        pool.onAllChannelsDead = { dead.set(true) }

        val leasedA = pool.lease(1000)
        val leasedB = pool.lease(1000)

        pool.discard(leasedA)
        assertFalse("onAllChannelsDead should not fire while one channel remains", dead.get())

        pool.discard(leasedB)
        assertTrue("onAllChannelsDead should fire once the last channel is discarded", dead.get())
    }

    @Test
    fun `concurrent lease-release never double-hands-out a channel`() {
        val transports = listOf(fake(), fake(), fake())
        val pool = ChannelPool(transports)

        val threadCount = 10
        val iterations = 200
        val currentlyLeased = ConcurrentHashMap<UsbTransport, Boolean>()
        val violations = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.submit {
                startLatch.await()
                try {
                    repeat(iterations) {
                        val t = try {
                            pool.lease(500)
                        } catch (_: IOException) {
                            return@repeat
                        }
                        if (currentlyLeased.putIfAbsent(t, true) != null) {
                            violations.incrementAndGet()
                        } else {
                            // brief simulated work
                            Thread.yield()
                            currentlyLeased.remove(t)
                        }
                        pool.release(t)
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("threads did not finish in time", doneLatch.await(30, TimeUnit.SECONDS))
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals("a channel was leased to two threads at once", 0, violations.get())
    }
}
