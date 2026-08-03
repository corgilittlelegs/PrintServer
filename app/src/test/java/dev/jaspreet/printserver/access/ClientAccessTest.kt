package dev.jaspreet.printserver.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientAccessTest {
    @Test
    fun `exact address is canonical and matches only itself`() {
        val rule = Ipv4AccessRule.parse("192.168.1.42")!!
        assertEquals("192.168.1.42", rule.canonical)
        assertTrue(rule.matches("192.168.1.42"))
        assertFalse(rule.matches("192.168.1.43"))
    }

    @Test
    fun `cidr canonicalizes network and matches its range`() {
        val rule = Ipv4AccessRule.parse("192.168.1.99/24")!!
        assertEquals("192.168.1.0/24", rule.canonical)
        assertTrue(rule.matches("192.168.1.254"))
        assertFalse(rule.matches("192.168.2.1"))
    }

    @Test
    fun `zero and full prefixes behave explicitly`() {
        assertTrue(Ipv4AccessRule.parse("10.0.0.1/0")!!.matches("203.0.113.8"))
        assertFalse(Ipv4AccessRule.parse("10.0.0.1/32")!!.matches("10.0.0.2"))
    }

    @Test
    fun `parser rejects hostnames malformed addresses and ambiguous octets`() {
        listOf(
            "printer.local", "1.2.3", "1.2.3.4.5", "1.2.3.256", "1.2.-1.4",
            "01.2.3.4", "1.2.3.4/", "1.2.3.4/33", "1.2.3.4/24/1", "",
        ).forEach { assertNull("expected invalid: $it", Ipv4AccessRule.parse(it)) }
    }

    @Test
    fun `open policy permits unknown clients while empty restricted policy denies all`() {
        assertTrue(ClientAccessPolicy.OPEN.allows(null))
        val restricted = ClientAccessPolicy.restricted(emptyList())
        assertNotNull(restricted)
        assertFalse(restricted!!.allows("127.0.0.1"))
        assertFalse(restricted.allows(null))
    }

    @Test
    fun `restricted policy requires every rule to be valid`() {
        assertNull(ClientAccessPolicy.restricted(listOf("192.168.1.2", "bad")))
        assertTrue(ClientAccessPolicy.restricted(listOf("192.168.1.0/24"))!!.allows("192.168.1.8"))
    }

    @Test
    fun `gate reads replacement policy on every decision`() {
        var policy = ClientAccessPolicy.OPEN
        val gate = PolicyClientAccessGate({ policy })
        assertTrue(gate.allows("127.0.0.1", NetworkService.TIER2_IPP))
        policy = ClientAccessPolicy.restricted(emptyList())!!
        assertFalse(gate.allows("127.0.0.1", NetworkService.TIER2_IPP))
    }

    @Test
    fun `rejection reporting is rate limited and bounded by key`() {
        var now = 1_000L
        val reports = mutableListOf<String>()
        val gate = PolicyClientAccessGate(
            policyProvider = { ClientAccessPolicy.restricted(emptyList())!! },
            onRejected = { address, service -> reports += "$address:${service.name}" },
            clockMs = { now },
            rejectionWindowMs = 100,
            maxTrackedRejections = 2,
        )
        repeat(3) { gate.allows("10.0.0.1", NetworkService.ESCL_SCAN) }
        assertEquals(1, reports.size)
        now += 100
        gate.allows("10.0.0.1", NetworkService.ESCL_SCAN)
        assertEquals(2, reports.size)
    }
}
