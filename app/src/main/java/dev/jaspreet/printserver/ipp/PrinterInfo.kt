package dev.jaspreet.printserver.ipp

data class PrinterInfo(
    val makeAndModel: String,
    val formats: List<String>,
    val color: Boolean,
    val uuid: String?,          // bare UUID, no urn:uuid: prefix
    val urf: List<String>,      // AirPrint URF capability tokens, may be empty
)
