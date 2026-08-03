package dev.jaspreet.printserver.render

import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Reproducible mutation fuzz harness for the independent PWG parser.
 *
 * Unexpected runtime exceptions fail the test. IOException is the only permitted response to
 * malformed input. The fixed seed makes failures reproducible in normal Gradle/CI runs.
 */
class PwgRasterValidatorFuzzTest {
    @Test(timeout = 15_000)
    fun `arbitrary bytes and mutated valid streams fail closed`() {
        val random = Random(0x505747L)
        repeat(5_000) {
            fuzzOne(ByteArray(random.nextInt(4_096)).also(random::nextBytes))
        }

        val seed = pwgPage(width = 16, height = 8)
        File("build/fuzz-corpus/pwg").apply { mkdirs() }
            .resolve("valid-srgb.pwg")
            .writeBytes(seed)
        repeat(10_000) {
            val mutation = seed.copyOf()
            repeat(1 + random.nextInt(8)) {
                val index = random.nextInt(mutation.size)
                mutation[index] = random.nextInt(256).toByte()
            }
            fuzzOne(mutation)
        }

        // Every truncation around the trust-boundary transitions is part of the permanent corpus.
        listOf(0, 1, 3, 4, 5, 100, 4 + HEADER_BYTES - 1, 4 + HEADER_BYTES, seed.size - 1)
            .forEach { fuzzOne(seed.copyOf(it)) }
    }

    companion object {
        /** Standard byte-array entry point usable by coverage-guided JVM fuzz runners. */
        @JvmStatic
        fun fuzzOne(data: ByteArray) = PwgRasterJazzerTarget.fuzzerTestOneInput(data)
    }
}
