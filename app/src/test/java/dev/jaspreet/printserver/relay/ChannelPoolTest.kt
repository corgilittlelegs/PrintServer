package dev.jaspreet.printserver.relay

import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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
}
