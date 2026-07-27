package dev.jaspreet.printserver.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HpcupsResultTest {

    @Test
    fun `code 0 maps to Success`() {
        assertEquals(HpcupsResult.Success, HpcupsResult.fromCode(0))
    }

    @Test
    fun `positive codes map to RenderFailure preserving the code`() {
        for (code in listOf(1, 3, 42)) {
            val result = HpcupsResult.fromCode(code)
            assertTrue(result is HpcupsResult.RenderFailure)
            assertEquals(code, (result as HpcupsResult.RenderFailure).code)
        }
    }

    @Test
    fun `negative codes map to SetupFailure with a human-readable reason`() {
        val cases = mapOf(
            -1 to "generic",
            -2 to "string allocation",
            -3 to "byte array allocation",
            -4 to "output file",
            -5 to "input file",
            -6 to "pipe",
            -7 to "thread",
        )
        for ((code, expectedFragment) in cases) {
            val result = HpcupsResult.fromCode(code)
            assertTrue("code $code should be SetupFailure", result is HpcupsResult.SetupFailure)
            result as HpcupsResult.SetupFailure
            assertEquals(code, result.code)
            assertTrue(
                "reason for $code should mention '$expectedFragment', was '${result.reason}'",
                result.reason.contains(expectedFragment, ignoreCase = true),
            )
        }
    }

    @Test
    fun `unrecognized negative code still maps to SetupFailure with a fallback reason`() {
        val result = HpcupsResult.fromCode(-99)
        assertTrue(result is HpcupsResult.SetupFailure)
        result as HpcupsResult.SetupFailure
        assertEquals(-99, result.code)
        assertTrue(result.reason.isNotBlank())
    }
}
