package dev.jaspreet.printserver.render

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class PwgRasterValidatorTest {
    @Test
    fun `accepts bounded sRGB PWG rows without decoding pixels`() {
        val result = validate(pwgPage(width = 2, height = 2))
        assertEquals(1, result.pages)
        assertEquals(12L, result.decodedBytes)
    }

    @Test
    fun `accepts sGray and clear-to-end-of-row encoding`() {
        val result = validate(pwgPage(width = 8, height = 3, colorSpace = 18, numColors = 1, row = byteArrayOf(128.toByte())))
        assertEquals(1, result.pages)
        assertEquals(24L, result.decodedBytes)
    }

    @Test
    fun `counts multiple pages and decoded bytes`() {
        val first = pwgPage(width = 2, height = 2)
        val second = pwgPage(width = 1, height = 1).copyOfRange(4, pwgPage(width = 1, height = 1).size)
        val result = validate(first + second)
        assertEquals(2, result.pages)
        assertEquals(15L, result.decodedBytes)
    }

    @Test
    fun `rejects unsupported signature`() = rejects(pwgPage().also { it[3] = '3'.code.toByte() })

    @Test
    fun `rejects non-PWG media class`() = rejects(pwgPage().also { it[4] = 'X'.code.toByte() })

    @Test
    fun `rejects unsupported resolution`() = rejects(pwgPage().also { it.putUInt(4 + 276, 1_200) })

    @Test
    fun `rejects oversized dimensions before any allocation`() = rejects(
        pwgPage().also { it.putUInt(4 + 372, 10_001) },
    )

    @Test
    fun `rejects bytes per line inconsistent with width`() = rejects(
        pwgPage().also { it.putUInt(4 + 392, 7) },
    )

    @Test
    fun `rejects unsupported colorspace`() = rejects(
        pwgPage().also { it.putUInt(4 + 400, 6) },
    )

    @Test
    fun `rejects banded and planar color order`() = rejects(
        pwgPage().also { it.putUInt(4 + 396, 1) },
    )

    @Test
    fun `rejects device compression controls`() = rejects(
        pwgPage().also { it.putUInt(4 + 404, 1) },
    )

    @Test
    fun `rejects row repetition beyond height`() {
        val input = pwgPage(width = 2, height = 2)
        input[4 + HEADER_BYTES] = 2
        rejects(input)
    }

    @Test
    fun `rejects PackBits run crossing row boundary`() {
        val input = pwgPage(width = 1, height = 1, row = byteArrayOf(1, 1, 2, 3))
        rejects(input)
    }

    @Test
    fun `rejects truncated header and row payload`() {
        val valid = pwgPage()
        rejects(valid.copyOf(100))
        rejects(valid.copyOf(valid.size - 1))
    }

    @Test
    fun `rejects trailing bytes instead of hiding them from CUPS`() = rejects(pwgPage() + byteArrayOf(1))

    @Test
    fun `enforces page and decoded-byte totals`() {
        val page = pwgPage(width = 2, height = 2)
        val second = page.copyOfRange(4, page.size)
        rejects(page + second, RenderingLimits(maxPages = 1))
        rejects(page, RenderingLimits(maxTotalRasterBytes = 11))
    }

    @Test
    fun `enforces compressed input cap including exact-boundary EOF`() {
        val page = pwgPage()
        validate(page, RenderingLimits(maxRasterInputBytes = page.size.toLong()))
        rejects(page, RenderingLimits(maxRasterInputBytes = page.size.toLong() - 1))
    }

    private fun validate(
        bytes: ByteArray,
        limits: RenderingLimits = RenderingLimits(),
    ): PwgRasterValidator.Result = PwgRasterValidator(limits).validate(ByteArrayInputStream(bytes))

    private fun rejects(bytes: ByteArray, limits: RenderingLimits = RenderingLimits()) {
        try {
            validate(bytes, limits)
            throw AssertionError("Expected malformed PWG raster to be rejected")
        } catch (_: IOException) {
            // Expected: malformed client data is a normal validation failure.
        }
    }
}

/** Minimal valid PWG seed used by validator and mutation-fuzz tests. */
internal fun pwgPage(
    width: Int = 2,
    height: Int = 2,
    colorSpace: Int = 19,
    numColors: Int = 3,
    row: ByteArray? = null,
): ByteArray {
    val header = ByteArray(HEADER_BYTES)
    "PwgRaster".toByteArray(Charsets.US_ASCII).copyInto(header)
    header.putUInt(276, 300)
    header.putUInt(280, 300)
    header.putUInt(372, width)
    header.putUInt(376, height)
    header.putUInt(384, 8)
    header.putUInt(388, 8 * numColors)
    header.putUInt(392, width * numColors)
    header.putUInt(396, 0)
    header.putUInt(400, colorSpace)
    header.putUInt(420, numColors)

    val encodedRow = row ?: run {
        val literalPixels = width.coerceAtMost(128)
        require(literalPixels == width) { "test seed only supports rows up to 128 pixels" }
        byteArrayOf((257 - literalPixels).toByte()) + ByteArray(width * numColors) { it.toByte() }
    }
    val rowRepeat = byteArrayOf((height - 1).toByte())
    return "RaS2".toByteArray(Charsets.US_ASCII) + header + rowRepeat + encodedRow
}

internal const val HEADER_BYTES = 1796

internal fun ByteArray.putUInt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}
