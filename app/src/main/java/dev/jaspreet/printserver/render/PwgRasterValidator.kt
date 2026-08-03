package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Strict, allocation-bounded validation for the PWG Raster subset accepted by Tier 2.
 *
 * This parser is intentionally independent of CUPS: using cupsRasterReadHeader2 to validate
 * input before handing the same input to CUPS would leave parser bugs reachable. It validates
 * structure only and never materializes decoded rows.
 */
internal class PwgRasterValidator(
    private val limits: RenderingLimits = RenderingLimits(),
    private val allowedResolutions: Set<Int> = setOf(300, 600),
) {
    data class Result(val pages: Int, val decodedBytes: Long)

    fun validate(file: File): Result {
        val size = file.length()
        if (size <= 0L) throw invalid("empty input")
        if (size > limits.maxRasterInputBytes) {
            throw invalid("input is $size bytes; limit is ${limits.maxRasterInputBytes}")
        }
        return file.inputStream().buffered().use { validate(it) }
    }

    internal fun validate(input: InputStream): Result {
        val reader = BoundedReader(input, limits.maxRasterInputBytes)
        val magic = ByteArray(MAGIC.size)
        reader.readFully(magic, "file signature")
        if (!magic.contentEquals(MAGIC)) {
            throw invalid("unsupported signature; expected network-byte-order RaS2 PWG raster")
        }

        var pages = 0
        var totalDecodedBytes = 0L
        while (true) {
            val header = reader.readHeaderOrEof() ?: break
            pages++
            if (pages > limits.maxPages) {
                throw invalid("page count exceeds ${limits.maxPages}")
            }

            val page = parseHeader(header, pages)
            val decodedPageBytes = page.bytesPerLine.toLong() * page.height.toLong()
            if (decodedPageBytes > limits.maxTotalRasterBytes - totalDecodedBytes) {
                throw invalid("decoded raster data exceeds ${limits.maxTotalRasterBytes} bytes")
            }
            totalDecodedBytes += decodedPageBytes
            validateCompressedRows(reader, page, pages)
        }

        if (pages == 0) throw invalid("contains no pages")
        return Result(pages, totalDecodedBytes)
    }

    private fun parseHeader(header: ByteArray, pageNumber: Int): Page {
        if (header.asciiString(MEDIA_CLASS_OFFSET, STRING_FIELD_BYTES) != "PwgRaster") {
            throw invalid("page $pageNumber is not marked PwgRaster")
        }

        val xDpi = header.positiveInt(HW_RESOLUTION_OFFSET, "horizontal resolution", pageNumber)
        val yDpi = header.positiveInt(HW_RESOLUTION_OFFSET + UINT_BYTES, "vertical resolution", pageNumber)
        if (xDpi !in allowedResolutions || yDpi !in allowedResolutions) {
            throw invalid("page $pageNumber resolution ${xDpi}x$yDpi is unsupported")
        }

        val width = header.positiveInt(CUPS_WIDTH_OFFSET, "width", pageNumber)
        val height = header.positiveInt(CUPS_HEIGHT_OFFSET, "height", pageNumber)
        if (width > limits.maxRasterWidth || height > limits.maxRasterHeight) {
            throw invalid(
                "page $pageNumber dimensions ${width}x$height exceed " +
                    "${limits.maxRasterWidth}x${limits.maxRasterHeight}",
            )
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > limits.maxPixelsPerPage) {
            throw invalid("page $pageNumber contains $pixels pixels; limit is ${limits.maxPixelsPerPage}")
        }

        val bitsPerColor = header.uint32(CUPS_BITS_PER_COLOR_OFFSET)
        val bitsPerPixel = header.uint32(CUPS_BITS_PER_PIXEL_OFFSET)
        val bytesPerLine = header.positiveInt(CUPS_BYTES_PER_LINE_OFFSET, "bytes per line", pageNumber)
        val colorOrder = header.uint32(CUPS_COLOR_ORDER_OFFSET)
        val colorSpace = header.uint32(CUPS_COLOR_SPACE_OFFSET)
        val compression = header.uint32(CUPS_COMPRESSION_OFFSET)
        val rowCount = header.uint32(CUPS_ROW_COUNT_OFFSET)
        val rowFeed = header.uint32(CUPS_ROW_FEED_OFFSET)
        val rowStep = header.uint32(CUPS_ROW_STEP_OFFSET)
        val numColors = header.uint32(CUPS_NUM_COLORS_OFFSET)

        if (colorOrder != CUPS_ORDER_CHUNKED) {
            throw invalid("page $pageNumber uses unsupported color order $colorOrder")
        }
        val expectedColors = when (colorSpace) {
            CUPS_CSPACE_SW -> 1L
            CUPS_CSPACE_SRGB -> 3L
            else -> throw invalid("page $pageNumber uses unsupported color space $colorSpace")
        }
        if (bitsPerColor != 8L || numColors != expectedColors || bitsPerPixel != 8L * expectedColors) {
            throw invalid("page $pageNumber must use 8-bit chunked sGray or sRGB pixels")
        }
        val expectedBytesPerLine = (width.toLong() * bitsPerPixel + 7L) / 8L
        if (bytesPerLine.toLong() != expectedBytesPerLine) {
            throw invalid(
                "page $pageNumber bytes per line $bytesPerLine does not match $expectedBytesPerLine",
            )
        }
        if (compression != 0L || rowCount != 0L || rowFeed != 0L || rowStep != 0L) {
            throw invalid("page $pageNumber contains unsupported device compression or row controls")
        }

        return Page(width, height, bytesPerLine, (bitsPerPixel / 8L).toInt())
    }

    private fun validateCompressedRows(reader: BoundedReader, page: Page, pageNumber: Int) {
        var rows = 0
        while (rows < page.height) {
            val repeatedRows = reader.readUnsignedByte("page $pageNumber row repeat count") + 1
            if (repeatedRows > page.height - rows) {
                throw invalid("page $pageNumber row repetition exceeds declared height")
            }

            var decoded = 0
            while (decoded < page.bytesPerLine) {
                val control = reader.readUnsignedByte("page $pageNumber row control")
                if (control == PACKBITS_CLEAR_REMAINDER) {
                    decoded = page.bytesPerLine
                } else if (control >= PACKBITS_LITERAL_MIN) {
                    val literalBytes = (257 - control) * page.bytesPerPixel
                    if (literalBytes > page.bytesPerLine - decoded) {
                        throw invalid("page $pageNumber literal run crosses the row boundary")
                    }
                    reader.skipFully(literalBytes, "page $pageNumber literal pixels")
                    decoded += literalBytes
                } else {
                    val repeatedBytes = (control + 1) * page.bytesPerPixel
                    if (repeatedBytes > page.bytesPerLine - decoded) {
                        throw invalid("page $pageNumber repeated run crosses the row boundary")
                    }
                    reader.skipFully(page.bytesPerPixel, "page $pageNumber repeated pixel")
                    decoded += repeatedBytes
                }
            }
            rows += repeatedRows
        }
    }

    private data class Page(
        val width: Int,
        val height: Int,
        val bytesPerLine: Int,
        val bytesPerPixel: Int,
    )

    private class BoundedReader(private val input: InputStream, private val maxBytes: Long) {
        private var consumed = 0L

        fun readHeaderOrEof(): ByteArray? {
            val first = readOptionalByte() ?: return null
            val header = ByteArray(PAGE_HEADER_BYTES)
            header[0] = first.toByte()
            readFully(header, 1, header.size - 1, "page header")
            return header
        }

        fun readFully(destination: ByteArray, what: String) =
            readFully(destination, 0, destination.size, what)

        private fun readFully(destination: ByteArray, offset: Int, length: Int, what: String) {
            var position = offset
            val end = offset + length
            while (position < end) {
                ensureCanConsume((end - position).toLong())
                val read = input.read(destination, position, end - position)
                if (read < 0) throw invalid("truncated $what")
                if (read == 0) continue
                position += read
                consumed += read.toLong()
            }
        }

        fun readUnsignedByte(what: String): Int = readOptionalByte() ?: throw invalid("truncated $what")

        private fun readOptionalByte(): Int? {
            if (consumed == maxBytes) {
                if (input.read() >= 0) throw invalid("input exceeds $maxBytes bytes")
                return null
            }
            ensureCanConsume(1L)
            val value = input.read()
            if (value >= 0) consumed++
            return value.takeIf { it >= 0 }
        }

        fun skipFully(length: Int, what: String) {
            var remaining = length
            val scratch = ByteArray(minOf(length, 512))
            while (remaining > 0) {
                val chunk = minOf(remaining, scratch.size)
                readFully(scratch, 0, chunk, what)
                remaining -= chunk
            }
        }

        private fun ensureCanConsume(bytes: Long) {
            if (bytes > maxBytes - consumed) {
                throw invalid("input exceeds $maxBytes bytes")
            }
        }
    }

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte())
        private const val PAGE_HEADER_BYTES = 1796
        private const val STRING_FIELD_BYTES = 64
        private const val UINT_BYTES = 4

        private const val MEDIA_CLASS_OFFSET = 0
        private const val HW_RESOLUTION_OFFSET = 276
        private const val CUPS_WIDTH_OFFSET = 372
        private const val CUPS_HEIGHT_OFFSET = 376
        private const val CUPS_BITS_PER_COLOR_OFFSET = 384
        private const val CUPS_BITS_PER_PIXEL_OFFSET = 388
        private const val CUPS_BYTES_PER_LINE_OFFSET = 392
        private const val CUPS_COLOR_ORDER_OFFSET = 396
        private const val CUPS_COLOR_SPACE_OFFSET = 400
        private const val CUPS_COMPRESSION_OFFSET = 404
        private const val CUPS_ROW_COUNT_OFFSET = 408
        private const val CUPS_ROW_FEED_OFFSET = 412
        private const val CUPS_ROW_STEP_OFFSET = 416
        private const val CUPS_NUM_COLORS_OFFSET = 420

        private const val CUPS_ORDER_CHUNKED = 0L
        private const val CUPS_CSPACE_SW = 18L
        private const val CUPS_CSPACE_SRGB = 19L
        private const val PACKBITS_CLEAR_REMAINDER = 128
        private const val PACKBITS_LITERAL_MIN = 129

        private fun ByteArray.uint32(offset: Int): Long =
            ((this[offset].toLong() and 0xffL) shl 24) or
                ((this[offset + 1].toLong() and 0xffL) shl 16) or
                ((this[offset + 2].toLong() and 0xffL) shl 8) or
                (this[offset + 3].toLong() and 0xffL)

        private fun ByteArray.positiveInt(offset: Int, name: String, page: Int): Int {
            val value = uint32(offset)
            if (value == 0L || value > Int.MAX_VALUE.toLong()) {
                throw invalid("page $page has invalid $name $value")
            }
            return value.toInt()
        }

        private fun ByteArray.asciiString(offset: Int, length: Int): String {
            var end = offset
            val limit = offset + length
            while (end < limit && this[end].toInt() != 0) {
                val value = this[end].toInt() and 0xff
                if (value !in 0x20..0x7e) throw invalid("page header contains a non-ASCII string")
                end++
            }
            if (end == limit) throw invalid("page header string is not NUL-terminated")
            return String(this, offset, end - offset, Charsets.US_ASCII)
        }

        private fun invalid(detail: String) = IOException("Invalid PWG raster: $detail")
    }
}
