package dev.jaspreet.printserver.scan

import dev.jaspreet.printserver.usb.UsbTransport
import java.io.IOException

data class SupplyStatus(
    val cartridges: List<SupplyCartridge>,
    val sourcePath: String,
)

data class SupplyCartridge(
    val name: String,
    val color: String? = null,
    val type: String? = null,
    val levelPercent: Int? = null,
    val state: String? = null,
    val message: String? = null,
)

/** Queries HP LEDM device-management XML for cartridge/ink supply state.
 *
 *  HPLIP exposes ink levels via its higher-level tools, but this Android app only
 *  bundles the hpcups print filter. For this printer family, the useful raw source is
 *  the device's LEDM `/DevMgmt/...Dyn.xml` status resources over the same USB MFP
 *  channel used by scanning. HP's XML names vary by firmware, so parsing is deliberately
 *  namespace/prefix tolerant and accepts several common percent/status field names. */
object LedmSupplyStatus {
    val CANDIDATE_PATHS = listOf(
        "/DevMgmt/ConsumableConfigDyn.xml",
        "/DevMgmt/ConsumableStatusDyn.xml",
        "/DevMgmt/ProductStatusDyn.xml",
    )

    fun query(
        openTransport: () -> UsbTransport,
        host: String = "localhost",
        paths: List<String> = CANDIDATE_PATHS,
    ): SupplyStatus {
        var lastFailure: Exception? = null
        for (path in paths) {
            try {
                val xml = fetch(openTransport, path, host)
                val cartridges = parseCartridges(xml)
                if (cartridges.isNotEmpty()) {
                    return SupplyStatus(cartridges = cartridges, sourcePath = path)
                }
                lastFailure = IOException("No consumable entries in $path")
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw IOException(
            "Supply status unavailable: ${lastFailure?.message ?: "no supported LEDM endpoint"}",
            lastFailure,
        )
    }

    fun fetch(openTransport: () -> UsbTransport, path: String, host: String = "localhost"): String =
        openTransport().let { transport ->
            try {
            val requestBytes = LedmRequests.deviceMgmtXmlRequest(path, host).toByteArray(Charsets.UTF_8)
            transport.write(requestBytes, 0, requestBytes.size)
            val reader = PullReader {
                val buf = ByteArray(16384)
                val n = transport.read(buf)
                buf.copyOf(n)
            }
            val header = ChunkedHttp.readHeader(reader)
            if (!header.startsWith("HTTP/1.1 2")) {
                throw IOException("HTTP status was ${header.lineSequence().firstOrNull() ?: "unknown"}")
            }
            String(readHttpBody(header, reader), Charsets.UTF_8)
            } finally {
                transport.close()
            }
        }

    fun parseCartridges(xml: String): List<SupplyCartridge> {
        val blocks = consumableBlocks(xml)
        return blocks.mapNotNull { block ->
            val name = firstTagText(
                block,
                "ConsumableLabelCode",
                "ConsumableLabel",
                "ConsumableName",
                "MarkerName",
                "Name",
            )?.prettyToken()
                ?: firstTagText(block, "ConsumableColor", "MarkerColor", "Color")?.prettyToken()
                ?: return@mapNotNull null

            val level = firstTagText(
                block,
                "ConsumablePercentageLevelRemaining",
                "ConsumableRawPercentageLevelRemaining",
                "MarkerLevel",
                "Level",
            )?.toPercent()
            val state = firstTagText(
                block,
                "ConsumableState",
                "ConsumableStatus",
                "MarkerState",
                "State",
                "Status",
            )?.prettyToken()
            val message = firstTagText(
                block,
                "ConsumableStatusMessage",
                "ConsumableMessage",
                "MarkerMessage",
                "Message",
            )?.prettyToken()

            SupplyCartridge(
                name = name,
                color = firstTagText(block, "ConsumableColor", "MarkerColor", "Color")?.prettyToken(),
                type = firstTagText(block, "ConsumableType", "MarkerType", "Type")?.prettyToken(),
                levelPercent = level,
                state = state,
                message = message,
            )
        }.distinctBy { cartridge ->
            listOf(
                cartridge.name.lowercase(),
                cartridge.color?.lowercase().orEmpty(),
                cartridge.type?.lowercase().orEmpty(),
            )
        }
    }

    private fun readHttpBody(header: String, reader: PullReader): ByteArray {
        val transferEncoding = headerValue(header, "Transfer-Encoding")
        if (transferEncoding?.contains("chunked", ignoreCase = true) == true) {
            return ChunkedHttp.readChunkedBody(reader)
        }
        val contentLength = headerValue(header, "Content-Length")?.trim()?.toIntOrNull()
        if (contentLength != null) {
            return reader.readExactly(contentLength)
        }
        throw IOException("Unsupported supply status response framing")
    }

    private fun consumableBlocks(xml: String): List<String> {
        val blocks = mutableListOf<String>()
        val pairedBlock = Regex(
            "<(?:[A-Za-z0-9_]+:)?(ConsumableInfo|Consumable|Marker)[^>]*>.*?</(?:[A-Za-z0-9_]+:)?\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        pairedBlock.findAll(xml).forEach { blocks += it.value }
        if (blocks.isNotEmpty()) return blocks

        // Some devices return useful fields directly under a status root. Treat the
        // whole document as one entry only if it has at least a recognizable label/color.
        return if (
            firstTagText(xml, "ConsumableLabelCode", "ConsumableLabel", "MarkerName", "ConsumableColor") != null
        ) {
            listOf(xml)
        } else {
            emptyList()
        }
    }

    private fun firstTagText(xml: String, vararg tags: String): String? {
        for (tag in tags) {
            val match = Regex(
                "<(?:[A-Za-z0-9_]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$tag>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(xml)
            val value = match?.groupValues?.get(1)?.stripXml()?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun headerValue(header: String, name: String): String? =
        header.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun String.stripXml(): String =
        replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

    private fun String.toPercent(): Int? =
        Regex("-?\\d+").find(this)?.value?.toIntOrNull()?.coerceIn(0, 100)

    private fun String.prettyToken(): String =
        trim()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
