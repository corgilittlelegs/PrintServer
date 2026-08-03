package dev.jaspreet.printserver.render

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Process
import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import dev.jaspreet.printserver.profile.VerifiedPrinterProfiles
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Private `:renderer` process. It owns native libraries but no network or USB resources. */
class RendererProcessService : Service() {
    private val rendering = AtomicBoolean(false)

    private val binder = object : IRendererService.Stub() {
        override fun getRendererPid(): Int = Process.myPid()

        override fun render(
            inputPath: String,
            outputPath: String,
            documentFormat: String,
            quality: String,
            colorMode: String,
            profileId: String,
        ): String {
            if (!rendering.compareAndSet(false, true)) return "Renderer is already busy"
            return try {
                validateText(documentFormat, "document format")
                validateText(quality, "quality")
                validateText(colorMode, "color mode")
                validateText(profileId, "profile id")
                val input = privateFile(inputPath, mustExist = true)
                val output = privateFile(outputPath, mustExist = false)
                if (input == output) throw IOException("Input and output paths must differ")
                val profile = VerifiedPrinterProfiles.all.singleOrNull { it.id == profileId }
                    ?: throw IOException("Unknown renderer profile")
                val resolvedQuality = enumValueOf<PrintQuality>(quality)
                val resolvedColorMode = enumValueOf<ColorMode>(colorMode)

                if (documentFormat == TEST_HANG_FORMAT) {
                    if (!isDebuggable()) throw IOException("Test renderer format is disabled")
                    // Instrumentation-only hook. The latch is deliberately never released;
                    // only terminating this process can end the call.
                    CountDownLatch(1).await()
                } else {
                    if (documentFormat !in profile.documentFormatsSupported) {
                        throw IOException("Unsupported document format")
                    }
                    val ppd = privateFile(PpdAsset.extract(this@RendererProcessService).absolutePath, mustExist = true)
                    NativeRenderingPipeline(
                        workDir = cacheDir,
                        ppdPath = ppd.absolutePath,
                        profileId = profile.id,
                    ).render(input, output, documentFormat, resolvedQuality, resolvedColorMode)
                }
                ""
            } catch (e: Exception) {
                (e.message ?: e.javaClass.simpleName).take(MAX_ERROR_LENGTH)
            } finally {
                rendering.set(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun privateFile(path: String, mustExist: Boolean): File {
        if (path.length > MAX_PATH_LENGTH) throw IOException("Renderer path is too long")
        val root = File(applicationInfo.dataDir).canonicalFile
        val file = File(path).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) {
            throw IOException("Renderer path is outside app-private storage")
        }
        if (mustExist && (!file.isFile || !file.canRead())) throw IOException("Renderer input is unavailable")
        if (!mustExist) {
            val parent = file.parentFile ?: throw IOException("Renderer output has no parent")
            if (!parent.isDirectory || !parent.canWrite()) throw IOException("Renderer output directory is unavailable")
        }
        return file
    }

    private fun validateText(value: String, label: String) {
        if (value.isEmpty() || value.length > MAX_TEXT_LENGTH) throw IOException("Invalid $label")
    }

    private fun isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        internal const val TEST_HANG_FORMAT = "application/x-printserver-renderer-hang"
        private const val MAX_PATH_LENGTH = 4096
        private const val MAX_TEXT_LENGTH = 128
        private const val MAX_ERROR_LENGTH = 1024
    }
}
