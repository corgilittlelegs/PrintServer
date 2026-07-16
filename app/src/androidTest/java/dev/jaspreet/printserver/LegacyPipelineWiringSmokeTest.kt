package dev.jaspreet.printserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jaspreet.printserver.render.NativeRenderingPipeline
import dev.jaspreet.printserver.render.PpdAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-only: re-proves the native chain through the exact construction ServerService uses. */
@RunWith(AndroidJUnit4::class)
class LegacyPipelineWiringSmokeTest {

    @Test
    fun rendersOnePagePdfToPcl() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val pdf = File(ctx.cacheDir, "smoke.pdf")
        val out = File(ctx.cacheDir, "smoke.pcl")
        val failureDir = File(ctx.cacheDir, "wiring-smoke-failure")
        testCtx.assets.open("smoke.pdf").use { input -> pdf.outputStream().use { input.copyTo(it) } }

        try {
            NativeRenderingPipeline(ctx.cacheDir, PpdAsset.extract(ctx).absolutePath).render(pdf, out)
            assertTrue("PCL output should be non-trivial", out.length() > 1024)
            assertEquals("PCL output should start with ESC", 0x1B, out.inputStream().use { it.read() })
        } catch (e: Throwable) {
            failureDir.mkdirs()
            pdf.copyTo(File(failureDir, "smoke.pdf"), overwrite = true)
            if (out.exists()) out.copyTo(File(failureDir, "smoke.pcl"), overwrite = true)
            throw AssertionError(
                "Wiring smoke test failed; artifacts saved to ${failureDir.absolutePath}, " +
                    "inspect with `adb logcat -s hpcupsjni gsjni`", e,
            )
        }
    }
}
