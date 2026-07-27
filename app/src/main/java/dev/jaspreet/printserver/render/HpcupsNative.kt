package dev.jaspreet.printserver.render

/**
 * JNI bridge to hpcups (HPLIP's raster -> PCL3-GUI encoder).
 *
 * IMPORTANT — concurrency contract: [encodeGuarded] and [encodeRasterGuarded] wrap hpcups's C
 * entry points, which mutate process-global statics (`hpcupsjni.cpp` documents both native
 * functions as "NOT thread-safe (globals + hpcups statics): callers must serialize.") and
 * are not reentrant. **Exactly one call into this object may be in flight in this process at
 * any time.** Today that's guaranteed structurally by [dev.jaspreet.printserver.jobs.JobQueue]:
 * every render goes through its single-threaded `renderExecutor`, so only one thread ever
 * reaches these entry points at once. Do not add a second caller (a parallel render path, a
 * background pre-render, etc.) without preserving that single-threaded guarantee — and always
 * call through [encodeGuarded]/[encodeRasterGuarded], never the raw `encode`/`encodeRaster`
 * JNI methods directly (they're `private` for exactly this reason).
 *
 * [guard] is an always-on runtime tripwire on top of that structural guarantee, not a
 * substitute for it: if a future change ever lets two calls overlap — a JobQueue threading
 * bug, a retry racing a still-running render — it throws immediately instead of silently
 * corrupting hpcups's global state or deadlocking. See [NativeCallGuard]'s kdoc for why this
 * throws rather than blocks.
 */
object HpcupsNative {
    init { System.loadLibrary("hpcupsjni") }

    private val guard = NativeCallGuard("HpcupsNative")

    /** [options] is a CUPS-style options string, e.g. "ColorModel=RGB OutputMode=Normal".
     *  Returns 0 on success, a negative setup-failure code, or a positive hpcups-internal
     *  exit code — see [HpcupsResult.fromCode]. */
    fun encodeGuarded(
        rgb: ByteArray, width: Int, height: Int, dpi: Int,
        ppdPath: String, outPath: String, options: String,
    ): Int = guard.guarded { encode(rgb, width, height, dpi, ppdPath, outPath, options) }

    /** Encodes a client-supplied PWG/CUPS raster file to printer-ready PCL3-GUI.
     *  [options] is a CUPS-style options string, e.g. "ColorModel=KGray OutputMode=FastDraft". */
    fun encodeRasterGuarded(inputPath: String, ppdPath: String, outPath: String, options: String): Int =
        guard.guarded { encodeRaster(inputPath, ppdPath, outPath, options) }

    // Raw JNI entry points. Their *names* are load-bearing: hpcupsjni.cpp binds to them via
    // the implicit `Java_dev_jaspreet_printserver_render_HpcupsNative_<methodName>` symbol
    // convention, so they can't be renamed here without also changing the native side (out of
    // scope for this task). Private so nothing outside this object can bypass [guard] by
    // calling them directly — always go through [encodeGuarded]/[encodeRasterGuarded].
    private external fun encode(
        rgb: ByteArray, width: Int, height: Int, dpi: Int,
        ppdPath: String, outPath: String, options: String,
    ): Int

    private external fun encodeRaster(inputPath: String, ppdPath: String, outPath: String, options: String): Int
}

/**
 * Categorizes the raw [Int] return codes from [HpcupsNative.encodeGuarded]/[HpcupsNative.encodeRasterGuarded].
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
