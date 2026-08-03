package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException

data class RenderingLimits(
    val maxPages: Int = 25,
    val maxPixelsPerPage: Long = 50_000_000L,
    val maxTotalRasterBytes: Long = 500L * 1_000_000L,
    val maxEncodedBytes: Long = 500L * 1_000_000L,
    val maxRasterInputBytes: Long = 400L * 1_000_000L,
    val maxRasterWidth: Int = 10_000,
    val maxRasterHeight: Int = 15_000,
)

internal fun validateRenderedPages(pages: List<File>, limits: RenderingLimits) {
    if (pages.size > limits.maxPages) {
        throw IOException("Document produced ${pages.size} pages; limit is ${limits.maxPages}")
    }
    var total = 0L
    for (page in pages) {
        val length = page.length()
        if (length > limits.maxTotalRasterBytes - total) {
            throw IOException("Decoded raster output exceeds ${limits.maxTotalRasterBytes} bytes")
        }
        total += length
    }
}
