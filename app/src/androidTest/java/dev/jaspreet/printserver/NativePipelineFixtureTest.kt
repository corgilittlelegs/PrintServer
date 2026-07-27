package dev.jaspreet.printserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import dev.jaspreet.printserver.render.GhostscriptRenderer
import dev.jaspreet.printserver.render.HpcupsNative
import dev.jaspreet.printserver.render.NativeRenderingPipeline
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
            val code = HpcupsNative.encodeGuarded(
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

    /**
     * Exercises the real production entry point (`NativeRenderingPipeline.render`), which
     * internally uses a `%03d`-pattern Ghostscript output so a multi-page PDF emits one PPM
     * per page, encodes each page through hpcups, and concatenates the results — unlike
     * [rendersOnePagePdfToPcl] above, which calls Ghostscript/hpcups directly for a single page.
     * Renders both the one-page and two-page fixtures through this same entry point so the
     * two output sizes are directly comparable (same dpi/options), then asserts the two-page
     * output is strictly larger, without pinning either to an exact byte count.
     */
    @Test
    fun rendersTwoPagePdfToPcl() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val workDir = File(ctx.cacheDir, "fixture-work").apply { mkdirs() }
        val ppdPath = PpdAsset.extract(ctx).absolutePath
        val pipeline = NativeRenderingPipeline(workDir, ppdPath)

        val onePagePdf = File(ctx.cacheDir, "fixture-onepage.pdf")
        val twoPagePdf = File(ctx.cacheDir, "fixture-twopage.pdf")
        val onePageOut = File(ctx.cacheDir, "fixture-onepage.pcl")
        val twoPageOut = File(ctx.cacheDir, "fixture-twopage.pcl")
        val failureDir = File(ctx.cacheDir, "fixture-failure")

        try {
            testCtx.assets.open("smoke.pdf").use { input -> onePagePdf.outputStream().use { input.copyTo(it) } }
            testCtx.assets.open("multipage-smoke.pdf").use { input -> twoPagePdf.outputStream().use { input.copyTo(it) } }

            pipeline.render(onePagePdf, onePageOut, "application/pdf", PrintQuality.NORMAL, ColorMode.COLOR)
            pipeline.render(twoPagePdf, twoPageOut, "application/pdf", PrintQuality.NORMAL, ColorMode.COLOR)

            assertTrue("two-page PCL output should be non-trivial", twoPageOut.length() > 1024)
            assertEquals(
                "two-page PCL output should start with ESC",
                0x1B,
                twoPageOut.inputStream().use { it.read() },
            )
            assertTrue(
                "two-page output (${twoPageOut.length()} bytes) should be larger than " +
                    "one-page output (${onePageOut.length()} bytes)",
                twoPageOut.length() > onePageOut.length(),
            )
        } catch (e: Throwable) {
            failureDir.mkdirs()
            onePagePdf.copyTo(File(failureDir, "fixture-onepage.pdf"), overwrite = true)
            twoPagePdf.copyTo(File(failureDir, "fixture-twopage.pdf"), overwrite = true)
            if (onePageOut.exists()) onePageOut.copyTo(File(failureDir, "fixture-onepage.pcl"), overwrite = true)
            if (twoPageOut.exists()) twoPageOut.copyTo(File(failureDir, "fixture-twopage.pcl"), overwrite = true)
            throw AssertionError(
                "Multi-page native pipeline fixture failed; artifacts saved to ${failureDir.absolutePath} " +
                    "(pull with `adb pull`), inspect with `adb logcat -s hpcupsjni gsjni`", e,
            )
        }
    }
}
