package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Operation
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
            Operation.getPrinterAttributes.code, Operation.getJobAttributes.code,
            Operation.cancelJob.code,
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
    )

    fun toPrinterInfo(): PrinterInfo =
        PrinterInfo(makeAndModel, formats, color, uuid.toString(), urf = emptyList())

    companion object {
        fun deskJet2300(printerUri: URI, uuid: UUID = STABLE_UUID): PrinterCapabilities =
            PrinterCapabilities(
                makeAndModel = "HP DeskJet 2300 series",
                formats = listOf("application/pdf"),
                color = true,
                printerUri = printerUri,
                uuid = uuid,
            )

        // Fixed so clients don't see a "new printer" after every app restart.
        private val STABLE_UUID: UUID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")
    }
}
