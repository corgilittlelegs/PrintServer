package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException

/**
 * Ghostscript (PDF -> PPM pages) then hpcups (RGB -> PCL3-GUI).
 * Multi-page PDFs: ppmraw with %d in the output name emits one file per page;
 * pages are encoded in order and concatenated into [output].
 */
class NativeRenderingPipeline(
    private val workDir: File,
    private val ppdPath: String,
    private val dpi: Int = 300,
) : RenderingPipeline {

    private val ghostscript = GhostscriptRenderer(dpi)

    override fun render(pdf: File, output: File) {
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
