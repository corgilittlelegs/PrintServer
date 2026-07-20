package dev.jaspreet.printserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jaspreet.printserver.scan.ScanPipeline
import dev.jaspreet.printserver.usb.UsbPrinterManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-only, requires the real DeskJet 2300-series MFP connected via USB with a
 *  physical page placed on the flatbed before running. Confirms the LEDM scan pipeline
 *  end-to-end against real firmware -- see docs/superpowers/plans/2026-07-19-scan-pipeline.md
 *  Task 6 for how to interpret and fix a failure here. */
@RunWith(AndroidJUnit4::class)
class ScanPipelineHardwareTest {

    @Test
    fun scansOnePageToAValidJpeg() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = UsbPrinterManager(ctx)
        val device = manager.findPrinter()
            ?: throw AssertionError("No printer found -- is the DeskJet 2300-series MFP connected via USB?")
        manager.openScanTransport(device)?.close()
            ?: throw AssertionError("No LEDM scan interface (255/4) found on the connected device")

        val output = File(ctx.cacheDir, "hardware-scan-test.jpg")
        ScanPipeline({ manager.openScanTransport(device) ?: throw AssertionError("Scan interface disappeared") })
            .scan(output)
        assertTrue("Output file should be non-trivial", output.length() > 1024)
        val magic = output.inputStream().use { it.readNBytes(2) }
        assertTrue("Output should start with the JPEG magic bytes", magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte())
    }
}
