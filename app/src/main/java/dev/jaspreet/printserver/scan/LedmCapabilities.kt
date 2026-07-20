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
     *  KNOWN ISSUE (found during hardware verification, only partially resolved): on a
     *  real DeskJet 2300-series unit, LEDM requests over USB bulk are intermittently
     *  flaky in ways that don't reproduce consistently -- a request sometimes gets back
     *  a stale/misframed response (e.g. this query's ScanCaps body leaking into a
     *  later, unrelated request's read), sometimes gets no data at all. Two real,
     *  well-sourced causes were found and fixed by reading HPLIP's actual reference
     *  implementation (scan/sane/bb_ledm.c, io/hpmud/musb.c): (1) HPLIP clears both
     *  bulk endpoints' halt/data-toggle state on every USB channel close, which this
     *  codebase's `UsbPrinterManager.openInterface` now also does (see its own doc
     *  comment); (2) HPLIP opens a *fresh* connection per logical request rather than
     *  holding one open across a whole scan, which `ScanPipeline` now also does. Both
     *  measurably improved reliability in testing but did NOT fully eliminate the
     *  flakiness -- some requests, including this one, still occasionally see a
     *  zero-byte or misframed read even immediately after a fresh, halt-cleared open.
     *  Remaining candidates: a required settle delay after claiming the interface that
     *  HPLIP's libusb-based transport gets "for free" from OS-level scheduling but this
     *  Android implementation doesn't; or something about `Cookie: AccessCounter=new`
     *  always being sent literally rather than a real per-session value. Needs a USB
     *  traffic capture against real hardware (e.g. Wireshark with usbmon on a rooted
     *  device, or a PC running HPLIP against the same printer for a known-good
     *  reference capture) to pin down further -- don't treat scan failures here as a
     *  code bug in the caller without checking this file's history first. */
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
