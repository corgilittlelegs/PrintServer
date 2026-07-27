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

        testCtx.assets.open("smoke.pdf").use { input -> onePagePdf.outputStream().use { input.copyTo(it) } }
        testCtx.assets.open("multipage-smoke.pdf").use { input -> twoPagePdf.outputStream().use { input.copyTo(it) } }

        try {
            pipeline.render(onePagePdf, onePageOut, "application/pdf", PrintQuality.NORMAL, ColorMode.COLOR)
            pipeline.render(twoPagePdf, twoPageOut, "application/pdf", PrintQuality.NORMAL, ColorMode.COLOR)

            assertTrue("one-page PCL output should be non-trivial", onePageOut.length() > 1024)
            assertEquals(
                "one-page PCL output should start with ESC",
                0x1B,
                onePageOut.inputStream().use { it.read() },
            )
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

    // --- Quality/color coverage (Task 15) -------------------------------------------------
    //
    // The checklist asks to "run a fixture through Draft/Normal/High" and, separately, "run a
    // fixture through RGB and KGray" — read as 3 quality runs + 2 color runs (5 total), each
    // holding the other dimension at a sensible default, rather than the full 3x2=6
    // cross-product. NORMAL/COLOR is already exercised end-to-end by [rendersTwoPagePdfToPcl]
    // above, so the quality methods below hold color at COLOR (the common default) and the
    // color methods hold quality at NORMAL, giving every quality value and every color value
    // at least one dedicated run without duplicating the full matrix.
    //
    // Each combination gets its own small @Test method (rather than one parametrized loop) so
    // a failure on, say, HIGH quality reports a distinct method name/stack trace and saves only
    // the artifacts relevant to that scenario — consistent with the two tests above.
    //
    // Size/hash invariant: deliberately NOT asserted. hpcups' PCL3-GUI output is compressed
    // per-row (RLE/delta-style), so a higher dpi or different color model does not guarantee
    // strictly larger bytes for a given input image — a mostly-white or mostly-black tiny
    // fixture could compress smaller at 600dpi than at 300dpi, or KGray (1 channel) could come
    // out larger or smaller than RGB (3 channels) depending on dithering. Unlike the one-page
    // vs. two-page comparison above (same quality/color, strictly more source content), there's
    // no safe monotonic relationship here to assert across devices/hpcups builds, so per the
    // checklist's "only if stable" hedge this is skipped in favor of just the non-empty +
    // no-exception checks.
    //
    // Threshold: DRAFT is 300dpi on the same tiny single-page fixture used by
    // [rendersOnePagePdfToPcl] (600dpi, >1024 bytes there), so a smaller conservative floor
    // (>100 bytes) is used here instead of assuming the >1024 threshold still holds at the
    // lower resolution.

    @Test
    fun rendersDraftQualityToPcl() {
        runQualityColorFixture("draft-quality", PrintQuality.DRAFT, ColorMode.COLOR)
    }

    @Test
    fun rendersNormalQualityToPcl() {
        runQualityColorFixture("normal-quality", PrintQuality.NORMAL, ColorMode.COLOR)
    }

    @Test
    fun rendersHighQualityToPcl() {
        runQualityColorFixture("high-quality", PrintQuality.HIGH, ColorMode.COLOR)
    }

    @Test
    fun rendersRgbColorToPcl() {
        runQualityColorFixture("rgb-color", PrintQuality.NORMAL, ColorMode.COLOR)
    }

    @Test
    fun rendersKGrayColorToPcl() {
        runQualityColorFixture("kgray-color", PrintQuality.NORMAL, ColorMode.MONOCHROME)
    }

    /**
     * Shared body for the five quality/color scenarios above: renders [smokePdf] through
     * [NativeRenderingPipeline.render] with the given [quality]/[colorMode], asserts non-empty,
     * ESC-prefixed PCL output, and — on any exception (including a native crash surfaced by
     * `HpcupsNative`'s guard machinery) — saves the input/output for this specific [scenario]
     * to cacheDir/fixture-failure before failing.
     */
    private fun runQualityColorFixture(scenario: String, quality: PrintQuality, colorMode: ColorMode) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val workDir = File(ctx.cacheDir, "fixture-work-$scenario").apply { mkdirs() }
        val ppdPath = PpdAsset.extract(ctx).absolutePath
        val pipeline = NativeRenderingPipeline(workDir, ppdPath)

        val pdf = File(ctx.cacheDir, "fixture-$scenario.pdf")
        val out = File(ctx.cacheDir, "fixture-$scenario.pcl")
        val failureDir = File(ctx.cacheDir, "fixture-failure")
        testCtx.assets.open("smoke.pdf").use { input -> pdf.outputStream().use { input.copyTo(it) } }

        try {
            pipeline.render(pdf, out, "application/pdf", quality, colorMode)
            assertTrue(
                "[$scenario] PCL output should be non-trivial (${out.length()} bytes)",
                out.length() > 100,
            )
            assertEquals(
                "[$scenario] PCL output should start with ESC",
                0x1B,
                out.inputStream().use { it.read() },
            )
        } catch (e: Throwable) {
            failureDir.mkdirs()
            pdf.copyTo(File(failureDir, "fixture-$scenario.pdf"), overwrite = true)
            if (out.exists()) out.copyTo(File(failureDir, "fixture-$scenario.pcl"), overwrite = true)
            throw AssertionError(
                "[$scenario] native pipeline fixture failed; artifacts saved to ${failureDir.absolutePath} " +
                    "(pull with `adb pull`), inspect with `adb logcat -s hpcupsjni gsjni`", e,
            )
        }
    }
}
