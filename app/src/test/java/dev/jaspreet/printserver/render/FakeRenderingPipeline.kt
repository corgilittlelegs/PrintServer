package dev.jaspreet.printserver.render

import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import java.io.File
import java.io.IOException

class FakeRenderingPipeline(
    private val result: ByteArray = "FAKE-PCL".toByteArray(),
    private val failWith: IOException? = null,
) : RenderingPipeline {
    val rendered = mutableListOf<File>()
    val formats = mutableListOf<String>()
    val qualities = mutableListOf<PrintQuality>()
    val colorModes = mutableListOf<ColorMode>()

    override fun render(
        document: File,
        output: File,
        format: String,
        quality: PrintQuality,
        colorMode: ColorMode,
    ) {
        rendered += document
        formats += format
        qualities += quality
        colorModes += colorMode
        failWith?.let { throw it }
        output.writeBytes(result)
    }
}
