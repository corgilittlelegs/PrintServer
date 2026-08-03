package dev.jaspreet.printserver.render

/** A renderer whose currently executing call can be terminated without killing the print server. */
interface RecoverableRenderingPipeline : RenderingPipeline, AutoCloseable {
    /** Returns true only when the active renderer was safely terminated and a later call may proceed. */
    fun recoverFromTimeout(): Boolean

    override fun close() {}
}
