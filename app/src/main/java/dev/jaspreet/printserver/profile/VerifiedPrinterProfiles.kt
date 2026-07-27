package dev.jaspreet.printserver.profile

import dev.jaspreet.printserver.usb.DeviceIdInfo

/**
 * Allowlist gate for Tier 2 (native rendering) startup: `hpcups`/PCL3-GUI rendering in this app
 * has only been verified end-to-end against an HP DeskJet 2300 series printer. Before
 * `ServerService` starts the legacy pipeline for a printer with no IPP-USB support, it must
 * find a positive match here — otherwise an unrelated/unverified printer would silently get fed
 * PCL3-GUI bytes tuned for a different device.
 */
object VerifiedPrinterProfiles {

    /** DeviceID `MFG:HP;MDL:deskjet 2300 series;DES:deskjet 2300 series;` — see the bundled
     *  PPD `app/src/main/assets/ppd/hp_deskjet_2300_series.ppd`. The alias list mirrors that
     *  PPD's `*Product` lines, which list both the base "All-in-one" branding and the "Ink
     *  Advantage" branding used in some regions for the same physical printer family. */
    val DESKJET_2300 = VerifiedPrinterProfile(
        id = "hp-deskjet-2300-series",
        displayName = "HP DeskJet 2300 series",
        manufacturer = "HP",
        modelAliases = listOf(
            "deskjet 2300 series",
            "hp deskjet 2300 all-in-one",
            "hp deskjet ink advantage 2300 all-in-one",
        ),
    )

    val all: List<VerifiedPrinterProfile> = listOf(DESKJET_2300)

    /**
     * Returns the first verified profile that positively matches [deviceIdInfo] (and, when
     * available, the runtime USB [vendorId]/[productId]), or null if this printer is unknown.
     *
     * Manufacturer and model matching is case-insensitive since real device-id strings vary in
     * case across firmware/region variants. Manufacturer must be present and match one profile;
     * model must be present and match one of that profile's aliases. A profile's
     * [VerifiedPrinterProfile.requiredCommands], if non-empty, must have at least one entry
     * present (case-insensitive) in [DeviceIdInfo.commands]. VID/PID matching is supplementary
     * only: when a profile declares them and the runtime device reports them, a mismatch does
     * not by itself disqualify a manufacturer/model match, since we don't have a verified VID/PID
     * for every profile.
     */
    fun match(
        deviceIdInfo: DeviceIdInfo,
        vendorId: Int? = null,
        productId: Int? = null,
    ): VerifiedPrinterProfile? {
        val manufacturer = deviceIdInfo.manufacturer?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val model = deviceIdInfo.model?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return all.firstOrNull { profile ->
            profile.manufacturer.equals(manufacturer, ignoreCase = true) &&
                profile.modelAliases.any { alias -> alias.equals(model, ignoreCase = true) } &&
                (profile.requiredCommands.isEmpty() ||
                    profile.requiredCommands.any { required ->
                        deviceIdInfo.commands.any { it.equals(required, ignoreCase = true) }
                    })
        }
    }
}
