package dev.jaspreet.printserver.usb

data class DeviceIdInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val commands: List<String> = emptyList(),
)

/** Parses an IEEE 1284 Device ID string (e.g. "MFG:HP;CMD:PCL,PJL;MDL:DeskJet 2700 series;"). */
object DeviceId {
    fun parse(raw: String?): DeviceIdInfo {
        if (raw.isNullOrBlank()) return DeviceIdInfo()

        val fields = raw.split(";")
            .mapNotNull { segment ->
                val idx = segment.indexOf(':')
                if (idx <= 0) null
                else segment.substring(0, idx).trim().uppercase() to segment.substring(idx + 1).trim()
            }
            .toMap()

        val manufacturer = fields["MFG"] ?: fields["MANUFACTURER"]
        val model = fields["MDL"] ?: fields["MODEL"]
        val commands = (fields["CMD"] ?: fields["COMMAND SET"])
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        return DeviceIdInfo(manufacturer, model, commands)
    }
}
