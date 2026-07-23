package dev.jaspreet.printserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jaspreet.printserver.render.GhostscriptRenderer
import dev.jaspreet.printserver.render.HpcupsNative
import dev.jaspreet.printserver.render.PpdAsset
import dev.jaspreet.printserver.render.PpmImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-only: proves gs -> ppm -> hpcups produces PCL bytes, with none of
 * JobQueue/LocalIppServer/USB in the way. On failure, copies every
 * intermediate artifact to cacheDir/fixture-failure so a real device debug
 * session doesn't start from nothing.
 */
@RunWith(AndroidJUnit4::class)
class NativePipelineFixtureTest {

    @Test
    fun rendersOnePagePdfToPcl() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val pdf = File(ctx.cacheDir, "fixture.pdf")
        testCtx.assets.open("smoke.pdf").use { input -> pdf.outputStream().use { input.copyTo(it) } }

        val ppm = File(ctx.cacheDir, "fixture.ppm")
        val pcl = File(ctx.cacheDir, "fixture.pcl")
        val failureDir = File(ctx.cacheDir, "fixture-failure")

        try {
            GhostscriptRenderer(dpi = 300).renderToPpm(pdf, ppm)
            val img = ppm.inputStream().buffered().use { PpmImage.parse(it) }
            val code = HpcupsNative.encode(
                img.rgb, img.width, img.height, 300, PpdAsset.extract(ctx).absolutePath, pcl.absolutePath,
                "ColorModel=RGB OutputMode=Normal",
            )
            assertEquals("hpcups should return 0", 0, code)
            assertTrue("PCL output should be non-trivial", pcl.length() > 1024)
            assertEquals("PCL output should start with ESC", 0x1B, pcl.inputStream().use { it.read() })
        } catch (e: Throwable) {
            failureDir.mkdirs()
            pdf.copyTo(File(failureDir, "fixture.pdf"), overwrite = true)
            if (ppm.exists()) ppm.copyTo(File(failureDir, "fixture.ppm"), overwrite = true)
            if (pcl.exists()) pcl.copyTo(File(failureDir, "fixture.pcl"), overwrite = true)
            throw AssertionError(
                "Native pipeline fixture failed; artifacts saved to ${failureDir.absolutePath} " +
                    "(pull with `adb pull`), inspect with `adb logcat -s hpcupsjni gsjni`", e,
            )
        }
    }
}
