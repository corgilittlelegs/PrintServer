package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.IntType
import com.hp.jipp.encoding.KeywordType
import com.hp.jipp.encoding.NameType
import com.hp.jipp.encoding.TextType
import dev.jaspreet.printserver.scan.SupplyCartridge
import dev.jaspreet.printserver.scan.SupplyStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class IppMarkerAttributesTest {

    @Test
    fun `maps supply status to CUPS marker attributes`() {
        val attrs = IppMarkerAttributes.from(
            SupplyStatus(
                cartridges = listOf(
                    SupplyCartridge(name = "Black cartridge", color = "Black", type = "Ink cartridge", levelPercent = 63),
                    SupplyCartridge(name = "Tri color cartridge", color = "Cyan Magenta Yellow", levelPercent = 12, message = "Low ink"),
                ),
                sourcePath = "/DevMgmt/ConsumableConfigDyn.xml",
            ),
        ).associateBy { it.name }

        assertEquals(listOf("Black cartridge", "Tri color cartridge"), attrs["marker-names"]!!.strings())
        assertEquals(listOf("ink-cartridge", "ink-cartridge"), attrs["marker-types"]!!.strings())
        assertEquals(listOf("#000000", "#00FFFF#FF00FF#FFFF00"), attrs["marker-colors"]!!.strings())
        assertEquals(listOf(63, 12), IntType.Set("marker-levels").coerce(attrs["marker-levels"]!!)!!.toList())
        assertEquals(listOf("63% remaining", "Low ink"), attrs["marker-message"]!!.strings())
    }

    @Test
    fun `omits marker attributes when supply status is absent`() {
        assertEquals(emptyList<Any>(), IppMarkerAttributes.from(null))
    }
}
