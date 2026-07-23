package dev.jaspreet.printserver.scan

import dev.jaspreet.printserver.usb.UsbTransport
import java.io.IOException

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
     *  Hardware note: the successful DeskJet 2300-series path was matched against
     *  HPLIP's reference implementation (scan/sane/bb_ledm.c, io/hpmud/musb.c). The
     *  important details are: use the ff/cc/0 LEDM scan channel, open a fresh USB
     *  connection per logical LEDM request, resynchronize to the first HTTP/1.1 status
     *  line, and accept bodyless 201 Created responses. */
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
        val result = parse(body)
        // A misframed/truncated read (see this object's known-issue doc above) can
        // produce a response that "parses" without throwing but is actually empty --
        // <Platen>/<SupportedResolutions>/<ColorEntries> not found, so every field
        // silently defaults to 0/empty. Treat that the same as an outright failure
        // (throw, let the caller retry) rather than silently handing a real eSCL client
        // capabilities it can't build any valid scan request from.
        if (result.maxWidth <= 0 || result.maxHeight <= 0 ||
            result.supportedResolutions.isEmpty() || result.supportedColorModes.isEmpty()
        ) {
            throw IOException("ScanCaps response parsed to empty/degenerate capabilities: $result")
        }
        return result
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
