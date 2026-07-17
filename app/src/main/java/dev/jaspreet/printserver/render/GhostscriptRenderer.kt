package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException

/** Renders a PDF to a raw PPM (P6) file at fixed resolution via Ghostscript. */
class GhostscriptRenderer(private val dpi: Int = 300) {

    fun renderToPpm(pdf: File, outPpm: File) {
        // No -dQUIET: gs's own diagnostics (missing device, malformed PDF, etc.)
        // are what explain a gs_error_Fatal, and now route to logcat via
        // gsapi_set_stdio in gsjni.c instead of the invisible real stdout/stderr.
        val code = GhostscriptNative.run(
            arrayOf(
                "gs", "-dSAFER", "-dBATCH", "-dNOPAUSE",
                "-sDEVICE=ppmraw", "-r$dpi",
                "-o", outPpm.absolutePath,
                pdf.absolutePath,
            )
        )
        if (code != 0) throw IOException("Ghostscript failed with code $code")
        // gs expands a "%03d"-style pattern itself into one file per page, so
        // outPpm never exists as a literal path in that case — check the
        // directory instead. Single-file (non-pattern) callers keep the
        // original exact-file check.
        val producedSomething = if (outPpm.name.contains('%')) {
            outPpm.parentFile?.listFiles { f -> f.name.endsWith(".ppm") }?.isNotEmpty() == true
        } else {
            outPpm.exists() && outPpm.length() > 0L
        }
        if (!producedSomething) throw IOException("Ghostscript produced no output")
    }
}
