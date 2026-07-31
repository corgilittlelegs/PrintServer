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
        // PWG raster used to be advertised here, but it sends untrusted LAN input directly into
        // the bundled CUPS/hpcups raster path. Keep it disabled until that native path has
        // strict header validation, dependency updates, and fuzz coverage.
        documentFormatsSupported = listOf("application/pdf", "image/jpeg"),
        mediaSupported = listOf("iso_a4_210x297mm", "na_letter_8.5x11in"),
        mediaDefault = "iso_a4_210x297mm",
        colorModesSupported = listOf("color", "monochrome"),
        qualityModesSupported = listOf("draft", "normal", "high"),
        qualityModeDefault = "normal",
        // NativeRenderingPipeline.dpiFor: DRAFT -> 300dpi, NORMAL/HIGH -> 600dpi (Best differs
        // from Normal only via hpcups OutputMode, not resolution). The bundled PPD's fourth
        // mode, Photo(1200dpi), is intentionally excluded: PrintOptions.kt documents that it has
        // no reachable IPP print-quality mapping today, so it must not be advertised until one
        // exists and an Android native fixture proves it works.
        resolutionsDpiSupported = listOf(300, 600),
        defaultResolutionDpi = 600,
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
     * present (case-insensitive) in [DeviceIdInfo.commands]. VID/PID matching is supplementary:
     * it only constrains the match when a profile actually declares [VerifiedPrinterProfile.vendorId]
     * / [VerifiedPrinterProfile.productId] — a profile without them (true for every profile today,
     * since none has a verified VID/PID yet) is unaffected by whatever the runtime device reports.
     * [profiles] defaults to [all] and exists so tests can exercise VID/PID matching against a
     * profile that declares one, without adding unverified VID/PID data to the real registry.
     */
    fun match(
        deviceIdInfo: DeviceIdInfo,
        vendorId: Int? = null,
        productId: Int? = null,
        profiles: List<VerifiedPrinterProfile> = all,
    ): VerifiedPrinterProfile? {
        val manufacturer = deviceIdInfo.manufacturer?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val model = deviceIdInfo.model?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return profiles.firstOrNull { profile ->
            profile.manufacturer.equals(manufacturer, ignoreCase = true) &&
                profile.modelAliases.any { alias -> alias.equals(model, ignoreCase = true) } &&
                (profile.requiredCommands.isEmpty() ||
                    profile.requiredCommands.any { required ->
                        deviceIdInfo.commands.any { it.equals(required, ignoreCase = true) }
                    }) &&
                (profile.vendorId == null || profile.vendorId == vendorId) &&
                (profile.productId == null || profile.productId == productId)
        }
    }
}
