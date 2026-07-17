package dev.jaspreet.printserver.render

object HpcupsNative {
    init { System.loadLibrary("hpcupsjni") }
    /** Returns 0 on success. Not thread-safe — serialize calls (JobQueue does). */
    external fun encode(
        rgb: ByteArray, width: Int, height: Int, dpi: Int,
        ppdPath: String, outPath: String,
    ): Int
}
