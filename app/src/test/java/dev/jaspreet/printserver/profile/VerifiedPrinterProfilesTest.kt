package dev.jaspreet.printserver.profile

import dev.jaspreet.printserver.usb.DeviceIdInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedPrinterProfilesTest {

    @Test
    fun `matches exact DeskJet 2300 device id`() {
        val info = DeviceIdInfo(
            manufacturer = "HP",
            model = "deskjet 2300 series",
            commands = listOf("PCL3GUI"),
        )
        val profile = VerifiedPrinterProfiles.match(info)
        assertEquals(VerifiedPrinterProfiles.DESKJET_2300, profile)
    }

    @Test
    fun `matches DeskJet 2300 via Ink Advantage alias, case-insensitively`() {
        val info = DeviceIdInfo(
            manufacturer = "hp",
            model = "HP Deskjet Ink Advantage 2300 All-in-one",
            commands = emptyList(),
        )
        val profile = VerifiedPrinterProfiles.match(info)
        assertEquals(VerifiedPrinterProfiles.DESKJET_2300, profile)
    }

    @Test
    fun `rejects a non-HP printer`() {
        val info = DeviceIdInfo(
            manufacturer = "Canon",
            model = "PIXMA MG3600",
            commands = listOf("BJL", "BJRaster3"),
        )
        val profile = VerifiedPrinterProfiles.match(info)
        assertNull(profile)
    }

    @Test
    fun `rejects an unsupported HP printer model`() {
        val info = DeviceIdInfo(
            manufacturer = "HP",
            model = "OfficeJet Pro 9010",
            commands = listOf("PCL", "PJL"),
        )
        val profile = VerifiedPrinterProfiles.match(info)
        assertNull(profile)
    }

    @Test
    fun `rejects when manufacturer or model is missing`() {
        assertNull(VerifiedPrinterProfiles.match(DeviceIdInfo(manufacturer = null, model = "deskjet 2300 series")))
        assertNull(VerifiedPrinterProfiles.match(DeviceIdInfo(manufacturer = "HP", model = null)))
        assertNull(VerifiedPrinterProfiles.match(DeviceIdInfo()))
    }

    @Test
    fun `vendor and product id are unconstrained for a profile that declares none`() {
        // DESKJET_2300 has no verified vendorId/productId, so any runtime value (or none) is fine.
        val info = DeviceIdInfo(manufacturer = "HP", model = "deskjet 2300 series")
        val profile = VerifiedPrinterProfiles.match(info, vendorId = 0x03F0, productId = 0x1234)
        assertEquals(VerifiedPrinterProfiles.DESKJET_2300, profile)
    }

    @Test
    fun `vendor and product id are enforced when a profile declares them`() {
        val profileWithVidPid = VerifiedPrinterProfile(
            id = "test-profile",
            displayName = "Test Printer",
            manufacturer = "HP",
            modelAliases = listOf("test model"),
            vendorId = 0x03F0,
            productId = 0x1234,
        )
        val info = DeviceIdInfo(manufacturer = "HP", model = "test model")

        val matched = VerifiedPrinterProfiles.match(
            info, vendorId = 0x03F0, productId = 0x1234, profiles = listOf(profileWithVidPid),
        )
        assertEquals(profileWithVidPid, matched)

        val mismatchedVendor = VerifiedPrinterProfiles.match(
            info, vendorId = 0x0000, productId = 0x1234, profiles = listOf(profileWithVidPid),
        )
        assertNull(mismatchedVendor)

        val missingRuntimeVidPid = VerifiedPrinterProfiles.match(
            info, vendorId = null, productId = null, profiles = listOf(profileWithVidPid),
        )
        assertNull(missingRuntimeVidPid)
    }
}
