package dev.jaspreet.printserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jaspreet.printserver.jobs.ColorMode
import dev.jaspreet.printserver.jobs.PrintQuality
import dev.jaspreet.printserver.profile.VerifiedPrinterProfiles
import dev.jaspreet.printserver.render.NativeRenderingPipeline
import dev.jaspreet.printserver.render.PpdAsset
import dev.jaspreet.printserver.usb.DeviceId
import dev.jaspreet.printserver.usb.UsbPrinterManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Explicit, opt-in hardware test: validated PWG -> native hpcups -> real legacy USB printer.
 * It is skipped unless instrumentation is launched with `-e hardwarePrint true`, preventing a
 * normal connectedAndroidTest run from unexpectedly consuming paper or ink.
 */
@RunWith(AndroidJUnit4::class)
class PwgPrinterHardwareTest {
    @Test
    fun printsValidatedPwgPageOnVerifiedDeskJet() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Physical printing requires -e hardwarePrint true",
            InstrumentationRegistry.getArguments().getString("hardwarePrint") == "true",
        )

        val context = instrumentation.targetContext
        val usb = UsbPrinterManager(context)
        val attachedDevice = usb.findPrinter()
        assertNotNull("No USB printer attached", attachedDevice)
        val device = attachedDevice!!
        assertTrue("USB permission missing for attached printer", usb.hasPermission(device))

        val deviceId = DeviceId.parse(usb.readDeviceId(device))
        val profile = VerifiedPrinterProfiles.match(deviceId, device.vendorId, device.productId)
        assertEquals(
            "Hardware print is allowed only for the verified DeskJet 2300 profile",
            VerifiedPrinterProfiles.DESKJET_2300.id,
            profile?.id,
        )

        val raster = File(context.cacheDir, "hardware-pwg-a4.pwg").apply {
            writeBytes(visibleA4PwgFixture())
        }
        val pcl = File(context.cacheDir, "hardware-pwg-a4.pcl").apply { delete() }
        NativeRenderingPipeline(
            context.cacheDir,
            PpdAsset.extract(context).absolutePath,
            profileId = profile!!.id,
        ).render(raster, pcl, "image/pwg-raster", PrintQuality.NORMAL, ColorMode.COLOR)

        assertTrue("Native PCL output should be non-trivial", pcl.length() > 1_000)
        assertEquals("PCL output should start with ESC", 0x1B, pcl.inputStream().use { it.read() })

        val transport = usb.openLegacyTransport(device)
            ?: throw AssertionError("Verified printer has no usable legacy USB transport")
        try {
            val buffer = ByteArray(65_536)
            pcl.inputStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    transport.write(buffer, 0, count)
                }
            }
        } finally {
            transport.close()
        }
    }

    /** A4/300dpi sRGB page with five full-width black bars near the top. */
    private fun visibleA4PwgFixture(): ByteArray {
        val width = 2_480
        val height = 3_508
        val header = ByteArray(1_796)
        header.putAscii(0, "PwgRaster")
        header.putAscii(128, "plain")
        header.putNetworkUInt(276, 300)
        header.putNetworkUInt(280, 300)
        header.putNetworkUInt(340, 1)
        header.putNetworkUInt(352, 595)
        header.putNetworkUInt(356, 842)
        header.putNetworkUInt(372, width)
        header.putNetworkUInt(376, height)
        header.putNetworkUInt(384, 8)
        header.putNetworkUInt(388, 24)
        header.putNetworkUInt(392, width * 3)
        header.putNetworkUInt(396, 0)
        header.putNetworkUInt(400, 19)
        header.putNetworkUInt(420, 3)
        header.putNetworkFloat(428, 595f)
        header.putNetworkFloat(432, 842f)
        header.putNetworkUInt(452, 1)

        val output = ByteArrayOutputStream(32_000)
        output.write("RaS2".toByteArray(Charsets.US_ASCII))
        output.write(header)

        var row = 0
        while (row < height) {
            val black = isBlackBar(row)
            var repeated = 1
            while (repeated < 256 && row + repeated < height && isBlackBar(row + repeated) == black) {
                repeated++
            }
            output.write(repeated - 1)
            if (black) writeSolidBlackRow(output, width) else output.write(128)
            row += repeated
        }
        return output.toByteArray()
    }

    private fun isBlackBar(row: Int): Boolean =
        row in 300 until 340 ||
            row in 500 until 540 ||
            row in 700 until 740 ||
            row in 900 until 940 ||
            row in 1_100 until 1_140

    private fun writeSolidBlackRow(output: ByteArrayOutputStream, width: Int) {
        var pixels = width
        while (pixels > 0) {
            val run = minOf(pixels, 128)
            output.write(run - 1)
            output.write(0)
            output.write(0)
            output.write(0)
            pixels -= run
        }
    }

    private fun ByteArray.putAscii(offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
    }

    private fun ByteArray.putNetworkFloat(offset: Int, value: Float) =
        putNetworkUInt(offset, java.lang.Float.floatToIntBits(value))

    private fun ByteArray.putNetworkUInt(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
