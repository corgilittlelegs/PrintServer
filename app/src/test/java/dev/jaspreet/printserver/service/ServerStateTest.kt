package dev.jaspreet.printserver.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ServerStateTest {

    @After
    fun resetState() {
        ServerState.update { ServerStatus() }
    }

    @Test
    fun `update preserves concurrent read-modify-write changes`() {
        ServerState.update { ServerStatus(port = 0) }
        val threads = 8
        val iterations = 500
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val executor = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            executor.execute {
                start.await(5, TimeUnit.SECONDS)
                repeat(iterations) {
                    ServerState.update { status -> status.copy(port = (status.port ?: 0) + 1) }
                }
                done.countDown()
            }
        }

        start.countDown()
        done.await(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertEquals(threads * iterations, ServerState.status.value.port)
    }
}
