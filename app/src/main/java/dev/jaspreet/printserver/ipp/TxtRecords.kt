package dev.jaspreet.printserver.ipp

/** DNS-SD TXT records for the _ipp._tcp advertisement (IPP Everywhere + AirPrint keys). */
object TxtRecords {
    fun forIpp(info: PrinterInfo): Map<String, String> = buildMap {
        put("txtvers", "1")
        put("qtotal", "1")
        put("rp", "ipp/print")
        put("ty", info.makeAndModel)
        put("pdl", info.formats.joinToString(","))
        put("color", if (info.color) "T" else "F")
        info.uuid?.let { put("UUID", it) }
        if (info.urf.isNotEmpty()) put("URF", info.urf.joinToString(","))
    }
}
