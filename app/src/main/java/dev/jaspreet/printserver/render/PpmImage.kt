package dev.jaspreet.printserver.render

import java.io.IOException
import java.io.InputStream

/** Minimal binary PPM (P6, maxval 255) reader — the format ppmraw emits. */
class PpmImage(val width: Int, val height: Int, val rgb: ByteArray) {

    companion object {
        fun parse(input: InputStream): PpmImage {
            if (nextToken(input) != "P6") throw IOException("Not a P6 PPM")
            val width = nextToken(input).toIntOrNull() ?: throw IOException("Bad width")
            val height = nextToken(input).toIntOrNull() ?: throw IOException("Bad height")
            val maxval = nextToken(input).toIntOrNull() ?: throw IOException("Bad maxval")
            if (maxval != 255) throw IOException("Unsupported maxval $maxval")
            // Compute in Long first: width * height * 3 as Int can silently overflow
            // and wrap negative, which would otherwise pass to ByteArray(negative).
            val expectedLong = width.toLong() * height.toLong() * 3L
            if (width <= 0 || height <= 0 || expectedLong > Int.MAX_VALUE) {
                throw IOException("Invalid or oversized PPM dimensions: ${width}x$height")
            }
            val expected = expectedLong.toInt()
            val rgb = ByteArray(expected)
            var read = 0
            while (read < expected) {
                val n = input.read(rgb, read, expected - read)
                if (n < 0) throw IOException("Truncated PPM: got $read of $expected bytes")
                read += n
            }
            return PpmImage(width, height, rgb)
        }

        /** Whitespace-delimited token reader that skips '#' comment lines. */
        private fun nextToken(input: InputStream): String {
            val sb = StringBuilder()
            var c = input.read()
            while (c != -1) {
                when {
                    c == '#'.code -> while (c != -1 && c != '\n'.code) c = input.read()
                    Character.isWhitespace(c) -> if (sb.isNotEmpty()) return sb.toString()
                    else -> sb.append(c.toChar())
                }
                c = input.read()
            }
            if (sb.isEmpty()) throw IOException("EOF in PPM header")
            return sb.toString()
        }
    }
}
