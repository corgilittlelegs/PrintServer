package dev.jaspreet.printserver.profile

/**
 * A printer model this app's Tier 2 (native rendering) pipeline has actually been verified
 * against on real hardware. Tier 2 renders with a specific PPD/hpcups combination tuned for
 * one printer family — running it against an unverified printer risks feeding it PCL3-GUI
 * bytes it can't interpret, or worse, bytes it silently misinterprets.
 *
 * [modelAliases] covers the different `MDL:` strings the same physical printer family reports
 * across firmware/region variants (see the bundled PPD's `*Product` lines for the DeskJet 2300
 * profile). All string matching is case-insensitive since real device-id strings vary in case.
 *
 * [vendorId]/[productId] are optional supplementary signals: when present on both the profile
 * and the runtime `UsbDevice`, a match adds confidence, but their absence is not disqualifying —
 * manufacturer/model/command matching is the primary gate.
 */
data class VerifiedPrinterProfile(
    val id: String,
    val displayName: String,
    val manufacturer: String,
    val modelAliases: List<String>,
    val requiredCommands: List<String> = emptyList(),
    val vendorId: Int? = null,
    val productId: Int? = null,
)
