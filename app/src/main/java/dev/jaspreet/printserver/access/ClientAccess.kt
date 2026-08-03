package dev.jaspreet.printserver.access

import java.util.LinkedHashMap

enum class ClientAccessMode { OPEN, RESTRICTED }

enum class NetworkService(val displayName: String, val tier: Int) {
    TIER1_IPP("Tier 1 IPP", 1),
    TIER2_IPP("Tier 2 IPP", 2),
    RAW_PRINT("raw printing", 2),
    ESCL_SCAN("scanner", 2),
}

/** Strict IPv4 or CIDR rule. Hostnames are intentionally never resolved. */
class Ipv4AccessRule private constructor(
    private val network: UInt,
    val prefixLength: Int,
) {
    val canonical: String
        get() = if (prefixLength == 32) format(network) else "${format(network)}/$prefixLength"

    fun matches(address: String?): Boolean {
        val parsed = parseAddress(address ?: return false) ?: return false
        return (parsed and mask(prefixLength)) == network
    }

    companion object {
        fun parse(value: String): Ipv4AccessRule? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            val slash = trimmed.indexOf('/')
            if (slash != trimmed.lastIndexOf('/')) return null
            val addressText = if (slash >= 0) trimmed.substring(0, slash) else trimmed
            val prefix = if (slash >= 0) {
                val text = trimmed.substring(slash + 1)
                if (text.isEmpty() || !text.all(Char::isDigit)) return null
                text.toIntOrNull()?.takeIf { it in 0..32 } ?: return null
            } else {
                32
            }
            val address = parseAddress(addressText) ?: return null
            return Ipv4AccessRule(address and mask(prefix), prefix)
        }

        internal fun parseAddress(value: String): UInt? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            var result = 0u
            for (part in parts) {
                if (part.isEmpty() || !part.all(Char::isDigit)) return null
                if (part.length > 1 && part[0] == '0') return null
                val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                result = (result shl 8) or octet.toUInt()
            }
            return result
        }

        private fun mask(prefix: Int): UInt = when (prefix) {
            0 -> 0u
            32 -> UInt.MAX_VALUE
            else -> UInt.MAX_VALUE shl (32 - prefix)
        }

        private fun format(address: UInt): String = listOf(24, 16, 8, 0)
            .joinToString(".") { shift -> ((address shr shift) and 0xffu).toString() }
    }
}

data class ClientAccessPolicy(
    val mode: ClientAccessMode = ClientAccessMode.OPEN,
    val rules: List<Ipv4AccessRule> = emptyList(),
) {
    fun allows(clientAddress: String?): Boolean =
        mode == ClientAccessMode.OPEN || rules.any { it.matches(clientAddress) }

    companion object {
        val OPEN = ClientAccessPolicy()

        fun restricted(ruleTexts: Iterable<String>): ClientAccessPolicy? {
            val parsed = ruleTexts.map { Ipv4AccessRule.parse(it) ?: return null }
            return ClientAccessPolicy(ClientAccessMode.RESTRICTED, parsed.distinctBy { it.canonical })
        }
    }
}

fun interface ClientAccessGate {
    fun allows(clientAddress: String?, service: NetworkService): Boolean

    companion object {
        val ALLOW_ALL = ClientAccessGate { _, _ -> true }
    }
}

/** Live policy gate with bounded, rate-limited rejection reporting. */
class PolicyClientAccessGate(
    private val policyProvider: () -> ClientAccessPolicy,
    private val onRejected: (clientAddress: String?, service: NetworkService) -> Unit = { _, _ -> },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val rejectionWindowMs: Long = 30_000L,
    private val maxTrackedRejections: Int = 256,
) : ClientAccessGate {
    private val rejectionTimes = LinkedHashMap<String, Long>(16, 0.75f, true)

    override fun allows(clientAddress: String?, service: NetworkService): Boolean {
        if (policyProvider().allows(clientAddress)) return true
        val key = "${clientAddress ?: "unknown"}|${service.name}"
        val now = clockMs()
        val shouldReport = synchronized(rejectionTimes) {
            val previous = rejectionTimes[key]
            rejectionTimes[key] = now
            while (rejectionTimes.size > maxTrackedRejections) {
                val eldest = rejectionTimes.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
            previous == null || now - previous >= rejectionWindowMs
        }
        if (shouldReport) onRejected(clientAddress, service)
        return false
    }
}
