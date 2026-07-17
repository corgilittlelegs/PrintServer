package dev.jaspreet.printserver.render

object GhostscriptNative {
    init {
        System.loadLibrary("gs")
        System.loadLibrary("gsjni")
    }
    /** Returns 0 on success; negative gsapi error code on failure. */
    external fun run(args: Array<String>): Int
}
