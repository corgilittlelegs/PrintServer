package dev.jaspreet.printserver.render

import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import java.io.File

/** Converts one spooled document (PDF or JPEG) into printer-ready bytes (PCL3-GUI for hpcups models). */
interface RenderingPipeline {
    /** Renders [document] ([format] is an IPP document-format MIME type) and writes printer bytes to [output]. Throws IOException on failure. */
    fun render(
        document: File,
        output: File,
        format: String = "application/pdf",
        quality: PrintQuality = PrintQuality.NORMAL,
        colorMode: ColorMode = ColorMode.COLOR,
    )
}
