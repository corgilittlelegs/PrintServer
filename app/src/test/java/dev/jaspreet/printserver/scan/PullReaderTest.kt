package dev.jaspreet.printserver.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class PullReaderTest {

    private fun readerOver(vararg pieces: ByteArray): PullReader {
        val queue = ArrayDeque(pieces.toList())
        return PullReader { queue.removeFirstOrNull() ?: throw IOException("exhausted") }
    }

    @Test
    fun `reads a line split across many small pulls`() {
        val reader = readerOver(
            "HE".toByteArray(),
            "LL".toByteArray(),
            "O\r".toByteArray(),
            "\n".toByteArray(),
        )
        assertEquals("HELLO", reader.readLine())
    }

    @Test
    fun `reads exactly n bytes across multiple pulls`() {
        val reader = readerOver("ab".toByteArray(), "cd".toByteArray(), "ef".toByteArray())
        assertArrayEquals("abcdef".toByteArray(), reader.readExactly(6))
    }

    @Test
    fun `readLine then readExactly share the buffer correctly`() {
        val reader = readerOver("hello\r\nworld!".toByteArray())
        assertEquals("hello", reader.readLine())
        assertArrayEquals("world!".toByteArray(), reader.readExactly(6))
    }

    @Test
    fun `throws when total buffered size exceeds the cap`() {
        val chunk = ByteArray(1024 * 1024) // 1 MB per pull
        val reader = PullReader { chunk }
        assertThrows(IOException::class.java) {
            // 64 MB cap / 1 MB chunks: this will exceed it well before 100 pulls.
            reader.readExactly(100 * 1024 * 1024)
        }
    }
}
