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
import java.net.URI
import java.util.UUID

/**
 * Hardcoded capabilities for a Tier-2 (host-based) printer — there is no
 * printer-side IPP to query, so the app is the source of truth.
 */
class PrinterCapabilities(
    val makeAndModel: String,
    val formats: List<String>,
    val color: Boolean,
    val printerUri: URI,
    val uuid: UUID,
) {
    fun asPrinterAttributes(): AttributeGroup = groupOf(
        Tag.printerAttributes,
        Types.printerUriSupported.of(printerUri),
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
        Types.mediaDefault.of("iso_a4_210x297mm"),
        Types.mediaSupported.of("iso_a4_210x297mm", "na_letter_8.5x11in"),
        Types.pdlOverrideSupported.of("attempted"),
        // Below: mandatory IPP Everywhere attributes. Without ippFeaturesSupported
        // advertising "ipp-everywhere" (plus the attributes IPP Everywhere requires
        // alongside it), macOS's driverless "Auto Select" can't identify this as a
        // driverless-capable printer and fails with "no driver found" even though
        // the printer is otherwise fully reachable and functional over IPP.
        Types.ippFeaturesSupported.of("ipp-everywhere"),
        Types.mediaColDatabase.of(
            MediaColDatabase(
                mediaSizeName = KeywordOrName("iso_a4_210x297mm"),
                mediaSize = MediaColDatabase.MediaSize(IntOrIntRange(21000), IntOrIntRange(29700)),
            ),
            MediaColDatabase(
                mediaSizeName = KeywordOrName("na_letter_8.5x11in"),
                mediaSize = MediaColDatabase.MediaSize(IntOrIntRange(21590), IntOrIntRange(27940)),
            ),
        ),
        Types.mediaColDefault.of(
            MediaCol(mediaSizeName = KeywordOrName("iso_a4_210x297mm"), mediaSize = MediaCol.MediaSize(21000, 29700)),
        ),
        Types.printColorModeSupported.of(if (color) listOf("color", "monochrome") else listOf("monochrome")),
        Types.printColorModeDefault.of(if (color) "color" else "monochrome"),
        Types.printerResolutionSupported.of(Resolution(300, 300, ResolutionUnit.dotsPerInch)),
        Types.printerResolutionDefault.of(Resolution(300, 300, ResolutionUnit.dotsPerInch)),
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
        Types.printQualityDefault.of(PrintQuality.normal),
        Types.printQualitySupported.of(PrintQuality.normal),
        Types.pagesPerMinute.of(8),
        Types.printerInfo.of(makeAndModel),
        Types.printerLocation.of(""),
        Types.printerMoreInfo.of(printerUri),
    )

    fun toPrinterInfo(): PrinterInfo =
        PrinterInfo(makeAndModel, formats, color, uuid.toString(), urf = URF_TOKENS)

    companion object {
        // PWG5100.13 URF tokens matching the resolution/color-mode/media attributes
        // declared in asPrinterAttributes() above. macOS's mDNS browse stage badges a
        // Bonjour _ipp._tcp service as AirPrint-capable using this TXT key alone, before
        // it ever opens an IPP connection — omit it and the printer shows up as an
        // unclassified generic Bonjour service instead.
        private val URF_TOKENS = listOf("V1.4", "CP1", "PQ4", "RS300", "W8", "SRGB24")

        fun deskJet2300(printerUri: URI, uuid: UUID = STABLE_UUID): PrinterCapabilities =
            PrinterCapabilities(
                // Deliberately not the real HP model string: an authentic vendor
                // model name in ty/printer-make-and-model can steer macOS's driver
                // picker toward reconciling against Apple's bundled HP driver
                // database instead of trusting the IPP-Everywhere self-declaration.
                makeAndModel = "PrintServer Bridge",
                formats = listOf("application/pdf", "image/pwg-raster", "image/jpeg"),
                color = true,
                printerUri = printerUri,
                uuid = uuid,
            )

        // Fixed so clients don't see a "new printer" after every app restart.
        private val STABLE_UUID: UUID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")
    }
}
