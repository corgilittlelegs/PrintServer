package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.IntOrIntRange
import com.hp.jipp.encoding.KeywordOrName
import com.hp.jipp.encoding.Resolution
import com.hp.jipp.encoding.ResolutionUnit
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Finishing
import com.hp.jipp.model.MediaCol
import com.hp.jipp.model.MediaColDatabase
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Orientation
import com.hp.jipp.model.PrintQuality
import com.hp.jipp.model.Types
import dev.jaspreet.printserver.profile.VerifiedPrinterProfile
import dev.jaspreet.printserver.profile.VerifiedPrinterProfiles
import java.net.URI
import java.util.UUID

/**
 * Tier-2 (host-based) printer capabilities — there is no printer-side IPP to query, so the app
 * is the source of truth. Built from a [VerifiedPrinterProfile] (see [fromProfile]) so the same
 * capability data feeds both the IPP `Get-Printer-Attributes` response ([asPrinterAttributes])
 * and the mDNS TXT record ([toPrinterInfo] -> [TxtRecords.forIpp]) — one source, so clients
 * never see mismatched offers between the two advertisement paths.
 */
class PrinterCapabilities(
    val makeAndModel: String,
    val formats: List<String>,
    val color: Boolean,
    val printerUri: URI,
    val uuid: UUID,
    val mediaSupported: List<String>,
    val mediaDefault: String,
    val colorModesSupported: List<String>,
    /** DPI values this printer's rendering pipeline actually produces — see
     *  `NativeRenderingPipeline.dpiFor`. Must never include unreachable resolutions
     *  (e.g. 1200dpi Photo mode) — see `PrintOptions.kt`. */
    val resolutionsDpiSupported: List<Int>,
    val defaultResolutionDpi: Int,
    val qualityModesSupported: List<PrintQuality>,
    val qualityModeDefault: PrintQuality,
) {
    private val createdAtNanos = System.nanoTime()

    fun asPrinterAttributes(): AttributeGroup = groupOf(
        Tag.printerAttributes,
        Types.printerUriSupported.of(printerUri),
        // RFC 2911 group-1 mandatory Printer attributes that IPP-Everywhere
        // clients (macOS's driverless picker included) validate before they'll
        // trust a printer enough to synthesize a driver for it.
        Types.uriAuthenticationSupported.of("none"),
        Types.uriSecuritySupported.of("none"),
        Types.multipleDocumentJobsSupported.of(false),
        Types.printerUpTime.of(((System.nanoTime() - createdAtNanos) / 1_000_000_000L).toInt()),
        Types.printerName.of(makeAndModel),
        Types.printerMakeAndModel.of(makeAndModel),
        Types.printerState.of(com.hp.jipp.model.PrinterState.idle),
        Types.printerStateReasons.of("none"),
        Types.printerIsAcceptingJobs.of(true),
        Types.printerUuid.of(URI.create("urn:uuid:$uuid")),
        Types.ippVersionsSupported.of("1.1", "2.0"),
        Types.operationsSupported.of(
            Operation.printJob.code, Operation.validateJob.code,
            Operation.createJob.code, Operation.sendDocument.code, Operation.closeJob.code,
            Operation.getPrinterAttributes.code, Operation.getJobAttributes.code, Operation.getJobs.code,
            Operation.cancelJob.code, Operation.cancelMyJobs.code, Operation.identifyPrinter.code,
        ),
        Types.charsetConfigured.of("utf-8"),
        Types.charsetSupported.of("utf-8"),
        Types.naturalLanguageConfigured.of("en"),
        Types.generatedNaturalLanguageSupported.of("en"),
        Types.documentFormatDefault.of(formats.first()),
        Types.documentFormatSupported.of(formats),
        Types.colorSupported.of(color),
        Types.compressionSupported.of("none"),
        Types.mediaDefault.of(mediaDefault),
        Types.mediaSupported.of(mediaSupported.map { KeywordOrName(it) }),
        Types.pdlOverrideSupported.of("attempted"),
        // Below: mandatory IPP Everywhere attributes. Without ippFeaturesSupported
        // advertising "ipp-everywhere" (plus the attributes IPP Everywhere requires
        // alongside it), macOS's driverless "Auto Select" can't identify this as a
        // driverless-capable printer and fails with "no driver found" even though
        // the printer is otherwise fully reachable and functional over IPP.
        Types.ippFeaturesSupported.of("ipp-everywhere"),
        Types.mediaColDatabase.of(mediaSupported.map { name -> mediaColDatabaseEntry(name) }),
        Types.mediaColDefault.of(mediaColEntry(mediaDefault)),
        Types.printColorModeSupported.of(colorModesSupported),
        Types.printColorModeDefault.of(if (color) "color" else "monochrome"),
        Types.printerResolutionSupported.of(
            resolutionsDpiSupported.sorted().map { dpi -> Resolution(dpi, dpi, ResolutionUnit.dotsPerInch) },
        ),
        Types.printerResolutionDefault.of(Resolution(defaultResolutionDpi, defaultResolutionDpi, ResolutionUnit.dotsPerInch)),
        Types.sidesSupported.of("one-sided"),
        Types.sidesDefault.of("one-sided"),
        Types.copiesDefault.of(1),
        Types.copiesSupported.of(1..1),
        Types.finishingsDefault.of(Finishing.none),
        Types.finishingsSupported.of(Finishing.none),
        Types.orientationRequestedDefault.of(Orientation.portrait),
        Types.orientationRequestedSupported.of(Orientation.portrait),
        Types.outputBinDefault.of(KeywordOrName("face-down")),
        Types.outputBinSupported.of(KeywordOrName("face-down")),
        Types.printQualityDefault.of(qualityModeDefault),
        Types.printQualitySupported.of(qualityModesSupported),
        Types.pagesPerMinute.of(8),
        Types.printerInfo.of(makeAndModel),
        Types.printerLocation.of(""),
        Types.printerMoreInfo.of(printerUri),
    )

    fun toPrinterInfo(): PrinterInfo =
        PrinterInfo(makeAndModel, formats, color, uuid.toString(), urf = urfTokens())

    // PWG5100.13 URF tokens matching the resolution/quality/color-mode/media attributes
    // declared in asPrinterAttributes() above. macOS's mDNS browse stage badges a Bonjour
    // _ipp._tcp service as AirPrint-capable using this TXT key alone, before it ever opens
    // an IPP connection — omit it and the printer shows up as an unclassified generic
    // Bonjour service instead. Per PWG5100.13, RS is a single "-"-joined token listing every
    // supported resolution (not one RS token per resolution), and PQ likewise lists every
    // supported print-quality code in one token.
    private fun urfTokens(): List<String> {
        val pq = qualityModesSupported.map { it.code }.sorted().joinToString("-")
        val rs = resolutionsDpiSupported.sorted().joinToString("-")
        return listOf("V1.4", "CP1", "PQ$pq", "RS$rs", "W8", "SRGB24")
    }

    private fun mediaColDatabaseEntry(mediaSizeName: String): MediaColDatabase {
        val (widthHundredthsMm, heightHundredthsMm) = mediaSizeHundredthsMm(mediaSizeName)
        return MediaColDatabase(
            mediaSizeName = KeywordOrName(mediaSizeName),
            mediaSize = MediaColDatabase.MediaSize(IntOrIntRange(widthHundredthsMm), IntOrIntRange(heightHundredthsMm)),
        )
    }

    private fun mediaColEntry(mediaSizeName: String): MediaCol {
        val (widthHundredthsMm, heightHundredthsMm) = mediaSizeHundredthsMm(mediaSizeName)
        return MediaCol(
            mediaSizeName = KeywordOrName(mediaSizeName),
            mediaSize = MediaCol.MediaSize(widthHundredthsMm, heightHundredthsMm),
        )
    }

    companion object {
        // Fixed so clients don't see a "new printer" after every app restart.
        private val STABLE_UUID: UUID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")

        // Deliberately not the real HP model string: an authentic vendor model name in
        // printer-make-and-model can steer macOS's driver picker toward reconciling against
        // Apple's bundled HP driver database instead of trusting the IPP-Everywhere
        // self-declaration.
        private const val BRIDGE_MAKE_AND_MODEL = "PrintServer Bridge"

        // PWG media size name -> (width, height) in hundredths of a millimeter, as required by
        // IPP's media-col-database/media-col-default. Grows as profiles declare new media sizes.
        private val MEDIA_SIZE_HUNDREDTHS_MM: Map<String, Pair<Int, Int>> = mapOf(
            "iso_a4_210x297mm" to (21000 to 29700),
            "na_letter_8.5x11in" to (21590 to 27940),
        )

        private fun mediaSizeHundredthsMm(name: String): Pair<Int, Int> =
            MEDIA_SIZE_HUNDREDTHS_MM[name]
                ?: throw IllegalArgumentException(
                    "Unknown media size '$name' — add its dimensions to MEDIA_SIZE_HUNDREDTHS_MM",
                )

        private fun toJippQuality(name: String): PrintQuality = when (name) {
            "draft" -> PrintQuality.draft
            "normal" -> PrintQuality.normal
            "high" -> PrintQuality.high
            else -> throw IllegalArgumentException("Unknown print quality mode '$name'")
        }

        /** Builds capabilities from a verified profile — the profile-declared document
         *  formats/media/color modes/quality modes/resolutions are advertised as-is, so a
         *  profile can never offer a client more than its rendering pipeline can deliver. */
        fun fromProfile(
            profile: VerifiedPrinterProfile,
            printerUri: URI,
            uuid: UUID = STABLE_UUID,
        ): PrinterCapabilities = PrinterCapabilities(
            makeAndModel = BRIDGE_MAKE_AND_MODEL,
            formats = profile.documentFormatsSupported,
            color = profile.colorModesSupported.contains("color"),
            printerUri = printerUri,
            uuid = uuid,
            mediaSupported = profile.mediaSupported,
            mediaDefault = profile.mediaDefault,
            colorModesSupported = profile.colorModesSupported,
            resolutionsDpiSupported = profile.resolutionsDpiSupported,
            defaultResolutionDpi = profile.defaultResolutionDpi,
            qualityModesSupported = profile.qualityModesSupported.map(::toJippQuality),
            qualityModeDefault = toJippQuality(profile.qualityModeDefault),
        )

        /** Convenience for the one verified profile that exists today; delegates to
         *  [fromProfile] so tests and call sites share the exact same construction path. */
        fun deskJet2300(printerUri: URI, uuid: UUID = STABLE_UUID): PrinterCapabilities =
            fromProfile(VerifiedPrinterProfiles.DESKJET_2300, printerUri, uuid)
    }
}
