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
/**
 * The Tier 2 (native rendering) capability fields below describe what this profile's
 * *rendering pipeline* can actually produce for this printer, not the printer's full spec
 * sheet — [PrinterCapabilities] (in the `ipp` package) advertises exactly these values over
 * IPP/mDNS so clients never see an offer the pipeline can't honor. Deliberately plain Kotlin
 * types (no JIPP/Android types) so `profile/` stays testable without those dependencies; the
 * `ipp` package is responsible for turning these into JIPP attribute values.
 *
 * [resolutionsDpiSupported] must match `NativeRenderingPipeline.dpiFor(quality)`'s real output
 * for every quality in [qualityModesSupported] — see that function's doc comment before
 * changing either list.
 */
data class VerifiedPrinterProfile(
    val id: String,
    val displayName: String,
    val manufacturer: String,
    val modelAliases: List<String>,
    val requiredCommands: List<String> = emptyList(),
    val vendorId: Int? = null,
    val productId: Int? = null,
    val documentFormatsSupported: List<String>,
    val mediaSupported: List<String>,
    val mediaDefault: String,
    /** IPP `print-color-mode-supported` keywords, e.g. "color"/"monochrome". */
    val colorModesSupported: List<String>,
    /** IPP print-quality keywords ("draft"/"normal"/"high") this pipeline can actually render. */
    val qualityModesSupported: List<String>,
    val qualityModeDefault: String,
    /** DPI values the rendering pipeline actually emits, one per reachable quality mode. */
    val resolutionsDpiSupported: List<Int>,
    val defaultResolutionDpi: Int,
)
