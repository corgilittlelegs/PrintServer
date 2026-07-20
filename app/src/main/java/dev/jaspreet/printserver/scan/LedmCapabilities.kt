package dev.jaspreet.printserver.scan

import dev.jaspreet.printserver.usb.UsbTransport

data class ScannerCapabilities(
    val maxWidth: Int,
    val maxHeight: Int,
    val supportedResolutions: List<Int>,
    val supportedColorModes: Set<ScanColorMode>,
)

/** Live-queries the scanner's real capabilities via GET /Scan/ScanCaps -- mirrors Tier
 *  1's PrinterQuery pattern (query real hardware) rather than Tier 2 print's hardcoded
 *  PrinterCapabilities, since this device's capabilities genuinely are queryable.
 *  <Platen> holds min/max width/height and a <SupportedResolutions> block of repeated
 *  <Resolution> elements, each wrapping an <XResolution>/<YResolution> pair (confirmed
 *  against a real HP DeskJet 2300-series unit -- X and Y are always equal on this
 *  device, so only XResolution is read); <ColorEntries> holds repeated <ColorType>
 *  tags. */
object LedmCapabilities {

    /** Queries the live device over [transport] and parses its response.
     *
     *  KNOWN ISSUE (found during hardware verification, unresolved): on a real DeskJet
     *  2300-series unit, issuing a GET /Scan/ScanCaps request appears to leave the LEDM
     *  session in a state where the *next* request on that USB interface -- even on a
     *  freshly reopened connection -- gets back a stale copy of this ScanCaps response
     *  instead of its own real response. This was confirmed by bypassing this query
     *  entirely: without it, the following GET /Scan/Status parsed correctly. With it
     *  (even via a separate, closed-before-reuse connection), Status broke every time.
     *  Root cause is not understood yet -- candidates include a device-side response
     *  cache keyed loosely by request shape, or something in the `Cookie:
     *  AccessCounter=new` handling that isn't a true per-request value. Needs a USB
     *  traffic capture against real hardware to pin down; don't call this from the same
     *  server-startup flow as an actual scan until that's resolved, or every scan will
     *  intermittently fail with "Scanner not idle: UNKNOWN". */
    fun query(transport: UsbTransport, host: String = "localhost"): ScannerCapabilities {
        val requestBytes = LedmRequests.scanCapsRequest(host).toByteArray(Charsets.UTF_8)
        transport.write(requestBytes, 0, requestBytes.size)
        val reader = PullReader {
            val buf = ByteArray(16384)
            val n = transport.read(buf)
            buf.copyOf(n)
        }
        ChunkedHttp.readHeader(reader)
        val body = String(ChunkedHttp.readChunkedBody(reader), Charsets.US_ASCII)
        return parse(body)
    }

    /** Pure parsing of an already-fetched ScanCaps XML body -- the seam JVM tests exercise. */
    fun parse(xml: String): ScannerCapabilities {
        val platen = xml.substringAfter("<Platen>", "").substringBefore("</Platen>")
        val maxWidth = tagInt(platen, "MaxWidth") ?: 0
        val maxHeight = tagInt(platen, "MaxHeight") ?: 0
        val supportedResolutionsBlock = platen
            .substringAfter("<SupportedResolutions>", "")
            .substringBefore("</SupportedResolutions>")
        val resolutions = Regex("<XResolution>(\\d+)</XResolution>").findAll(supportedResolutionsBlock)
            .map { it.groupValues[1].toInt() }
            .toList()

        val colorEntries = xml.substringAfter("<ColorEntries>", "").substringBefore("</ColorEntries>")
        val colorModes = Regex("<ColorType>(\\w+)</ColorType>").findAll(colorEntries)
            .mapNotNull {
                when (it.groupValues[1]) {
                    "Color8" -> ScanColorMode.COLOR
                    "Gray8" -> ScanColorMode.GRAYSCALE
                    else -> null // K1 (1-bit) -- not modeled, see ScanColorMode's doc comment
                }
            }
            .toSet()

        return ScannerCapabilities(maxWidth, maxHeight, resolutions, colorModes)
    }

    private fun tagInt(xml: String, tag: String): Int? =
        Regex("<$tag>(\\d+)</$tag>").find(xml)?.groupValues?.get(1)?.toIntOrNull()
}
