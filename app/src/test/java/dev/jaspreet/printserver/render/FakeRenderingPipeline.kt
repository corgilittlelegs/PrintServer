package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException

class FakeRenderingPipeline(
    private val result: ByteArray = "FAKE-PCL".toByteArray(),
    private val failWith: IOException? = null,
) : RenderingPipeline {
    val rendered = mutableListOf<File>()
    val formats = mutableListOf<String>()

    override fun render(document: File, output: File, format: String) {
        rendered += document
        formats += format
        failWith?.let { throw it }
        output.writeBytes(result)
    }
}
