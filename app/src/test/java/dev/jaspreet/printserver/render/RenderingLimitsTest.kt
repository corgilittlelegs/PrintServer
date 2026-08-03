package dev.jaspreet.printserver.render

import org.junit.Test
import java.io.File
import java.io.IOException

class RenderingLimitsTest {
    @Test(expected = IOException::class)
    fun `rejects more decoded pages than configured`() {
        val pages = (1..3).map { File("page-$it.ppm") }
        validateRenderedPages(pages, RenderingLimits(maxPages = 2))
    }

    @Test(expected = IOException::class)
    fun `rejects aggregate decoded raster bytes above configured limit`() {
        val dir = kotlin.io.path.createTempDirectory("render-limits").toFile()
        try {
            val first = File(dir, "one.ppm").apply { writeBytes(ByteArray(6)) }
            val second = File(dir, "two.ppm").apply { writeBytes(ByteArray(5)) }
            validateRenderedPages(listOf(first, second), RenderingLimits(maxTotalRasterBytes = 10))
        } finally {
            dir.deleteRecursively()
        }
    }
}
