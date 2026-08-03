package dev.jaspreet.printserver

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import dev.jaspreet.printserver.profile.VerifiedPrinterProfiles
import dev.jaspreet.printserver.render.RendererProcessPipeline
import dev.jaspreet.printserver.render.RendererProcessService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Device-only proof that a wedged native renderer can be killed without killing the app. */
@RunWith(AndroidJUnit4::class)
class RendererProcessRecoveryTest {

    @Test
    fun timeoutKillsRendererAndNextRenderUsesFreshProcess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        val mainPid = Process.myPid()
        val input = File(context.cacheDir, "renderer-recovery.pdf")
        val firstOutput = File(context.cacheDir, "renderer-recovery-first.pcl").apply { delete() }
        val secondOutput = File(context.cacheDir, "renderer-recovery-second.pcl").apply { delete() }
        testContext.assets.open("smoke.pdf").use { source ->
            input.outputStream().use { destination -> source.copyTo(destination) }
        }
        val pipeline = RendererProcessPipeline(
            context,
            VerifiedPrinterProfiles.DESKJET_2300.id,
            bindTimeoutMs = 10_000,
            terminationTimeoutMs = 5_000,
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val wedgedCall = executor.submit {
                pipeline.render(
                    input,
                    firstOutput,
                    RendererProcessService.TEST_HANG_FORMAT,
                    PrintQuality.NORMAL,
                    ColorMode.COLOR,
                )
            }
            val pidDeadline = System.currentTimeMillis() + 10_000
            var firstRendererPid = pipeline.activeRendererPidForTest()
            while (firstRendererPid <= 0 && System.currentTimeMillis() < pidDeadline) {
                Thread.sleep(20)
                firstRendererPid = pipeline.activeRendererPidForTest()
            }
            assertTrue("renderer process should become active", firstRendererPid > 0)
            assertNotEquals("native work must not run in the main app process", mainPid, firstRendererPid)

            assertTrue("verified renderer process should be terminated", pipeline.recoverFromTimeout())
            try {
                wedgedCall.get(5, TimeUnit.SECONDS)
                throw AssertionError("wedged Binder call should fail when renderer is killed")
            } catch (_: Exception) {
                // Expected: the synchronous Binder call ends because its remote process died.
            }
            assertEquals("main app process must survive renderer termination", mainPid, Process.myPid())

            pipeline.render(
                input,
                secondOutput,
                "application/pdf",
                PrintQuality.NORMAL,
                ColorMode.COLOR,
            )
            val secondRendererPid = pipeline.lastRendererPidForTest()
            assertTrue("replacement renderer should have a PID", secondRendererPid > 0)
            assertNotEquals("recovery must bind a fresh renderer process", firstRendererPid, secondRendererPid)
            assertTrue("replacement renderer should produce non-trivial PCL", secondOutput.length() > 1_024)
            assertEquals(0x1B, secondOutput.inputStream().use { it.read() })
        } finally {
            executor.shutdownNow()
            pipeline.close()
            input.delete()
            firstOutput.delete()
            secondOutput.delete()
        }
    }
}
