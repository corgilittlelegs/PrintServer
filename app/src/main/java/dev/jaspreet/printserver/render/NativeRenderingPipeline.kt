package dev.jaspreet.printserver.render

import android.graphics.BitmapFactory
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
    private val dpi: Int = 300,
) : RenderingPipeline {

    private val ghostscript = GhostscriptRenderer(dpi)

    override fun render(document: File, output: File, format: String) {
        when (format) {
            "image/jpeg" -> renderJpeg(document, output)
            "image/pwg-raster" -> renderPwgRaster(document, output)
            else -> renderPdf(document, output)
        }
    }

    private fun renderPwgRaster(raster: File, output: File) {
        val code = HpcupsNative.encodeRaster(raster.absolutePath, ppdPath, output.absolutePath)
        if (code != 0) throw IOException("hpcups failed with code $code for PWG Raster")
    }

    private fun renderJpeg(jpeg: File, output: File) {
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
            val code = HpcupsNative.encode(rgb, width, height, dpi, ppdPath, output.absolutePath)
            if (code != 0) throw IOException("hpcups failed with code $code")
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderPdf(pdf: File, output: File) {
        val pageDir = File(workDir, "pages-${System.nanoTime()}").apply { mkdirs() }
        try {
            val pattern = File(pageDir, "page-%03d.ppm")
            ghostscript.renderToPpm(pdf, pattern)
            val pages = pageDir.listFiles { f -> f.name.endsWith(".ppm") }?.sortedBy { it.name }
                ?: emptyList()
            if (pages.isEmpty()) throw IOException("Ghostscript produced no pages")

            output.outputStream().use { out ->
                for (page in pages) {
                    val img = page.inputStream().buffered().use { PpmImage.parse(it) }
                    val pageOut = File(pageDir, "${page.name}.pcl")
                    val code = HpcupsNative.encode(
                        img.rgb, img.width, img.height, dpi, ppdPath, pageOut.absolutePath,
                    )
                    if (code != 0) throw IOException("hpcups failed with code $code on ${page.name}")
                    pageOut.inputStream().use { it.copyTo(out) }
                }
            }
        } finally {
            pageDir.deleteRecursively()
        }
    }
}
