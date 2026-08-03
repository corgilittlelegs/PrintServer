package dev.jaspreet.printserver.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class PpmImageTest {

    private fun ppm(header: String, pixels: ByteArray) =
        ByteArrayInputStream(header.toByteArray(Charsets.US_ASCII) + pixels)

    @Test
    fun `parses P6 header and pixel data`() {
        val pixels = byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0) // 2x1: red, green
        val img = PpmImage.parse(ppm("P6\n2 1\n255\n", pixels))
        assertEquals(2, img.width)
        assertEquals(1, img.height)
        assertArrayEquals(pixels, img.rgb)
    }

    @Test
    fun `skips comment lines in header`() {
        val pixels = byteArrayOf(1, 2, 3)
        val img = PpmImage.parse(ppm("P6\n# ghostscript output\n1 1\n255\n", pixels))
        assertEquals(1, img.width)
        assertArrayEquals(pixels, img.rgb)
    }

    @Test(expected = IOException::class)
    fun `rejects non-P6 magic`() {
        PpmImage.parse(ppm("P3\n1 1\n255\n", byteArrayOf(1, 2, 3)))
    }

    @Test(expected = IOException::class)
    fun `rejects truncated pixel data`() {
        PpmImage.parse(ppm("P6\n2 2\n255\n", byteArrayOf(1, 2, 3))) // needs 12 bytes
    }

    @Test(expected = IOException::class)
    fun `rejects dimensions that would overflow pixel buffer size`() {
        // width * height * 3 as Int would overflow; must be rejected before allocating.
        PpmImage.parse(ppm("P6\n50000 50000\n255\n", ByteArray(0)))
    }

    @Test(expected = IOException::class)
    fun `rejects dimensions above configured pixel limit before allocation`() {
        PpmImage.parse(ppm("P6\n11 10\n255\n", ByteArray(0)), maxPixels = 100)
    }
}
