package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.Attribute
import com.hp.jipp.encoding.IntType
import com.hp.jipp.encoding.KeywordType
import com.hp.jipp.encoding.NameType
import com.hp.jipp.encoding.TextType
import dev.jaspreet.printserver.scan.SupplyCartridge
import dev.jaspreet.printserver.scan.SupplyStatus

/** Maps HP LEDM consumable status into the CUPS/IPP marker-* printer attributes
 *  that macOS asks for when displaying printer supply levels. These are CUPS
 *  extension attributes, so JIPP's generated IANA `Types` object doesn't define
 *  them; use local primitive attribute definitions with the standard names/tags. */
object IppMarkerAttributes {
    private val markerNames = NameType.Set("marker-names")
    private val markerTypes = KeywordType.Set("marker-types")
    private val markerColors = NameType.Set("marker-colors")
    private val markerLevels = IntType.Set("marker-levels")
    private val markerLowLevels = IntType.Set("marker-low-levels")
    private val markerHighLevels = IntType.Set("marker-high-levels")
    private val markerMessage = TextType.Set("marker-message")

    val attributeNames = setOf(
        "marker-names",
        "marker-types",
        "marker-colors",
        "marker-levels",
        "marker-low-levels",
        "marker-high-levels",
        "marker-message",
    )

    fun from(status: SupplyStatus?): List<Attribute<*>> {
        val cartridges = status?.cartridges?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(
            markerNames.ofStrings(cartridges.map { it.name }),
            markerTypes.of(cartridges.map { it.markerType() }),
            markerColors.ofStrings(cartridges.map { it.markerColor() }),
            markerLevels.of(cartridges.map { it.levelPercent ?: UNKNOWN_LEVEL }),
            markerLowLevels.of(cartridges.map { LOW_LEVEL }),
            markerHighLevels.of(cartridges.map { HIGH_LEVEL }),
            markerMessage.ofStrings(cartridges.map { it.markerMessage() }),
        )
    }

    private fun SupplyCartridge.markerType(): String {
        val raw = type ?: name
        return when {
            raw.contains("toner", ignoreCase = true) -> "toner"
            raw.contains("drum", ignoreCase = true) -> "opc"
            else -> "ink-cartridge"
        }
    }

    private fun SupplyCartridge.markerColor(): String {
        val raw = "${color.orEmpty()} $name"
        return when {
            raw.contains("black", ignoreCase = true) -> "#000000"
            raw.contains("cyan", ignoreCase = true) &&
                raw.contains("magenta", ignoreCase = true) &&
                raw.contains("yellow", ignoreCase = true) -> "#00FFFF#FF00FF#FFFF00"
            raw.contains("tri", ignoreCase = true) ||
                raw.contains("color", ignoreCase = true) -> "#00FFFF#FF00FF#FFFF00"
            raw.contains("cyan", ignoreCase = true) -> "#00FFFF"
            raw.contains("magenta", ignoreCase = true) -> "#FF00FF"
            raw.contains("yellow", ignoreCase = true) -> "#FFFF00"
            else -> "none"
        }
    }

    private fun SupplyCartridge.markerMessage(): String =
        listOfNotNull(state, message).distinct().joinToString(" · ").ifBlank {
            levelPercent?.let { "$it% remaining" } ?: "Level unknown"
        }

    private const val LOW_LEVEL = 10
    private const val HIGH_LEVEL = 100
    private const val UNKNOWN_LEVEL = -1
}
