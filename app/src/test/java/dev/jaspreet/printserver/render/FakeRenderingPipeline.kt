package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException

class FakeRenderingPipeline(
    private val result: ByteArray = "FAKE-PCL".toByteArray(),
    private val failWith: IOException? = null,
) : RenderingPipeline {
    val rendered = mutableListOf<File>()

    override fun render(pdf: File, output: File) {
        rendered += pdf
        failWith?.let { throw it }
        output.writeBytes(result)
    }
}
