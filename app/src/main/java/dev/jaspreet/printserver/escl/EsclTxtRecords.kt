package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScannerCapabilities

/** DNS-SD TXT records for the _uscan._tcp (eSCL) advertisement. */
object EsclTxtRecords {
    fun forEscl(capabilities: ScannerCapabilities, makeAndModel: String): Map<String, String> = mapOf(
        "txtvers" to "1",
        "ty" to makeAndModel,
        "rs" to "t", // "rs" (representation string) -- eSCL root resource path is /eSCL, "t" signals top-level
        "pdl" to "image/jpeg",
        "vers" to "2.63",
        "note" to "",
    )
}
