package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException

/** Renders a PDF to a raw PPM (P6) file at fixed resolution via Ghostscript. */
class GhostscriptRenderer(private val dpi: Int = 300) {

    fun renderToPpm(pdf: File, outPpm: File) {
        val code = GhostscriptNative.run(
            arrayOf(
                "gs", "-dSAFER", "-dBATCH", "-dNOPAUSE", "-dQUIET",
                "-sDEVICE=ppmraw", "-r$dpi",
                "-o", outPpm.absolutePath,
                pdf.absolutePath,
            )
        )
        if (code != 0) throw IOException("Ghostscript failed with code $code")
        if (!outPpm.exists() || outPpm.length() == 0L) {
            throw IOException("Ghostscript produced no output")
        }
    }
}
