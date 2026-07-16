package dev.jaspreet.printserver.render

import java.io.File

/** Converts one spooled PDF into printer-ready bytes (PCL3-GUI for hpcups models). */
interface RenderingPipeline {
    /** Renders [pdf] and writes printer bytes to [output]. Throws IOException on failure. */
    fun render(pdf: File, output: File)
}
