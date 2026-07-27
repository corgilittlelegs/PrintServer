package dev.jaspreet.printserver.render

object HpcupsNative {
    init { System.loadLibrary("hpcupsjni") }
    /** [options] is a CUPS-style options string, e.g. "ColorModel=RGB OutputMode=Normal".
     *  Returns 0 on success, a negative setup-failure code, or a positive hpcups-internal
     *  exit code — see [HpcupsResult.fromCode]. Not thread-safe — serialize calls (JobQueue does). */
    external fun encode(
        rgb: ByteArray, width: Int, height: Int, dpi: Int,
        ppdPath: String, outPath: String, options: String,
    ): Int

    /** Encodes a client-supplied PWG/CUPS raster file to printer-ready PCL3-GUI.
     *  [options] is a CUPS-style options string, e.g. "ColorModel=KGray OutputMode=FastDraft". */
    external fun encodeRaster(inputPath: String, ppdPath: String, outPath: String, options: String): Int
}

/**
 * Categorizes the raw [Int] return codes from [HpcupsNative.encode]/[HpcupsNative.encodeRaster].
 *
 * The negative codes are stable constants assigned in `hpcupsjni.cpp` (see the comment block
 * above `HPCUPS_ERR_GENERIC` there) for failures that happen *before* hpcups itself ever runs
 * (allocation/OOM, file open, pipe/thread setup). hpcups's own encoder (`hpcups_main`) only
 * ever returns 0 (success) or a small positive exit code on its own internal failure — those
 * codes aren't independently enumerated here since hpcups doesn't publish a table of them
 * that's safe to hardcode; they're surfaced as [RenderFailure] with the raw code preserved.
 */
sealed class HpcupsResult {
    object Success : HpcupsResult()

    /** Negative return code: hpcups never started — a setup step (alloc, file open, pipe,
     *  thread creation) failed before the encoder ran. */
    data class SetupFailure(val code: Int, val reason: String) : HpcupsResult()

    /** Positive return code: hpcups ran and reported its own internal failure. */
    data class RenderFailure(val code: Int) : HpcupsResult()

    companion object {
        fun fromCode(code: Int): HpcupsResult = when {
            code == 0 -> Success
            code < 0 -> SetupFailure(code, describeSetupError(code))
            else -> RenderFailure(code)
        }

        /** Human-readable description for each stable negative code defined in hpcupsjni.cpp.
         *  Keep in sync with that file's HPCUPS_ERR_* constants (don't renumber, only append). */
        private fun describeSetupError(code: Int): String = when (code) {
            -1 -> "generic/unexpected setup failure"
            -2 -> "string allocation failed (out of memory)"
            -3 -> "byte array allocation failed (out of memory)"
            -4 -> "failed to open output file"
            -5 -> "failed to open input file"
            -6 -> "pipe creation failed"
            -7 -> "thread creation failed"
            else -> "unknown setup failure"
        }
    }
}
