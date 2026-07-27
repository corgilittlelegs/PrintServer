package dev.jaspreet.printserver.render

import android.graphics.BitmapFactory
import android.util.Log
import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import java.io.File
import java.io.IOException

/**
 * Ghostscript (PDF -> PPM pages) or Android's built-in JPEG decoder (single-page
 * image/jpeg jobs), then hpcups (RGB -> PCL3-GUI). Multi-page PDFs: ppmraw with %d
 * in the output name emits one file per page; pages are encoded in order and
 * concatenated into [output].
 */
class NativeRenderingPipeline(
    private val workDir: File,
    private val ppdPath: String,
    /** Verified profile this pipeline was built from, if any — included in render-failure
     *  logs only (not part of the render path itself). Optional/defaulted so existing
     *  callers/tests that don't have a profile in scope don't need to change. */
    private val profileId: String? = null,
) : RenderingPipeline {

    companion object {
        private const val TAG = "NativeRenderingPipeline"

        // Bounds a decoded JPEG's memory footprint (ARGB_8888 bitmap + RGB copies below);
        // 50 megapixels is well beyond any realistic printed page at this pipeline's max dpi.
        private const val MAX_JPEG_PIXELS = 50_000_000L

        // FastDraft/Normal/Best all live in the bundled PPD; Best differs from Normal only
        // via the OutputMode option string below, not resolution — both render at 600dpi.
        // Photo(1200dpi) has no reachable IPP print-quality mapping (see PrintOptions.kt).
        //
        // internal (not private) and on the companion object so this is the single source of
        // truth other code can reference directly instead of duplicating the mapping by hand —
        // e.g. PrinterCapabilitiesTest asserts the IPP-advertised resolutions against this exact
        // function rather than reflecting into a private method or hand-copying the DRAFT=300/
        // NORMAL,HIGH=600 rule (which could then silently drift from the real renderer).
        internal fun dpiFor(quality: PrintQuality): Int = if (quality == PrintQuality.DRAFT) 300 else 600
    }

    override fun render(document: File, output: File, format: String, quality: PrintQuality, colorMode: ColorMode) {
        val dpi = dpiFor(quality)
        val options = hpcupsOptions(quality, colorMode)
        when (format) {
            "image/jpeg" -> renderJpeg(document, output, dpi, options, format, quality, colorMode)
            "image/pwg-raster" -> renderPwgRaster(document, output, options, format, quality, colorMode)
            else -> renderPdf(document, output, dpi, options, format, quality, colorMode)
        }
    }

    private fun hpcupsOptions(quality: PrintQuality, colorMode: ColorMode): String {
        val outputMode = when (quality) {
            PrintQuality.DRAFT -> "FastDraft"
            PrintQuality.NORMAL -> "Normal"
            PrintQuality.HIGH -> "Best"
        }
        val colorModel = if (colorMode == ColorMode.COLOR) "RGB" else "KGray"
        return "ColorModel=$colorModel OutputMode=$outputMode"
    }

    private fun renderPwgRaster(
        raster: File, output: File, options: String,
        format: String, quality: PrintQuality, colorMode: ColorMode,
    ) {
        val code = HpcupsNative.encodeRaster(raster.absolutePath, ppdPath, output.absolutePath, options)
        checkResult(code, format, quality, colorMode)
    }

    private fun renderJpeg(
        jpeg: File, output: File, dpi: Int, options: String,
        format: String, quality: PrintQuality, colorMode: ColorMode,
    ) {
        // Check the declared dimensions before decoding actual pixels — a tiny file can
        // claim an enormous width/height (decompression bomb) and blow up memory on the
        // full decode below. inJustDecodeBounds only parses the header, no pixel buffer.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpeg.absolutePath, bounds)
        val declaredPixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || declaredPixels > MAX_JPEG_PIXELS) {
            throw IOException("JPEG dimensions ${bounds.outWidth}x${bounds.outHeight} exceed limit")
        }
        val bitmap = BitmapFactory.decodeFile(jpeg.absolutePath)
            ?: throw IOException("Could not decode JPEG")
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            // hpcups expects packed RGB triplets, row-major — same layout PpmImage
            // produces from Ghostscript's ppmraw output.
            val rgb = ByteArray(width * height * 3)
            var i = 0
            for (pixel in pixels) {
                rgb[i++] = ((pixel shr 16) and 0xFF).toByte()
                rgb[i++] = ((pixel shr 8) and 0xFF).toByte()
                rgb[i++] = (pixel and 0xFF).toByte()
            }
            val code = HpcupsNative.encode(rgb, width, height, dpi, ppdPath, output.absolutePath, options)
            checkResult(code, format, quality, colorMode)
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderPdf(
        pdf: File, output: File, dpi: Int, options: String,
        format: String, quality: PrintQuality, colorMode: ColorMode,
    ) {
        val pageDir = File(workDir, "pages-${System.nanoTime()}").apply { mkdirs() }
        try {
            val pattern = File(pageDir, "page-%03d.ppm")
            GhostscriptRenderer(dpi).renderToPpm(pdf, pattern)
            val pages = pageDir.listFiles { f -> f.name.endsWith(".ppm") }?.sortedBy { it.name }
                ?: emptyList()
            if (pages.isEmpty()) throw IOException("Ghostscript produced no pages")

            output.outputStream().use { out ->
                for (page in pages) {
                    val img = page.inputStream().buffered().use { PpmImage.parse(it) }
                    val pageOut = File(pageDir, "${page.name}.pcl")
                    val code = HpcupsNative.encode(
                        img.rgb, img.width, img.height, dpi, ppdPath, pageOut.absolutePath, options,
                    )
                    checkResult(code, format, quality, colorMode, page = page.name)
                    pageOut.inputStream().use { it.copyTo(out) }
                }
            }
        } finally {
            pageDir.deleteRecursively()
        }
    }

    /**
     * Maps a raw hpcups return code to [HpcupsResult] and throws [IOException] on anything
     * but success. Logs the profile id (if known), format, quality, color mode, and (for
     * multi-page PDFs) which page failed, so a failure is diagnosable from Logcat alone —
     * `JobQueue.process()` catches this as a generic `Exception` and only records a coarse
     * `document-format-error` job-state reason, so this message/log line is the actual detail.
     */
    private fun checkResult(
        code: Int, format: String, quality: PrintQuality, colorMode: ColorMode, page: String? = null,
    ) {
        val result = HpcupsResult.fromCode(code)
        if (result is HpcupsResult.Success) return

        val context = buildString {
            append("profile=").append(profileId ?: "unknown")
            append(" format=").append(format)
            append(" quality=").append(quality)
            append(" colorMode=").append(colorMode)
            if (page != null) append(" page=").append(page)
        }
        val message = when (result) {
            is HpcupsResult.SetupFailure ->
                "hpcups setup failed (code ${result.code}: ${result.reason}) [$context]"
            is HpcupsResult.RenderFailure ->
                "hpcups render failed (exit code ${result.code}) [$context]"
            HpcupsResult.Success -> return // unreachable, satisfies exhaustiveness
        }
        Log.e(TAG, message)
        throw IOException(message)
    }
}
