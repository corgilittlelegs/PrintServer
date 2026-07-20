package dev.jaspreet.printserver.escl

import dev.jaspreet.printserver.scan.ScannerCapabilities

/** DNS-SD TXT records for the _uscan._tcp (eSCL) advertisement. */
object EsclTxtRecords {
    fun forEscl(capabilities: ScannerCapabilities, makeAndModel: String): Map<String, String> = mapOf(
        "txtvers" to "1",
        "ty" to makeAndModel,
        // "rs" is the root resource path segment eSCL/AirScan clients prepend to every
        // request URL (e.g. GET /{rs}/ScannerCapabilities) -- confirmed against real
        // client behavior that this must be the literal path this server serves under
        // ("eSCL", no leading/trailing slash), not any other placeholder value. Getting
        // this wrong makes clients compute a URL this server never receives a request
        // on, so scanning silently "fails to connect" despite mDNS discovery and direct
        // HTTP requests to /eSCL/... working fine.
        "rs" to "eSCL",
        "pdl" to "image/jpeg",
        "vers" to "2.63",
        "note" to "",
    )
}
