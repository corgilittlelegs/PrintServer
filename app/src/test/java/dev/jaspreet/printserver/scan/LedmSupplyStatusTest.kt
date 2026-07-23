package dev.jaspreet.printserver.scan

import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedmSupplyStatusTest {

    @Test
    fun `builds a device management XML request`() {
        val req = LedmRequests.deviceMgmtXmlRequest("/DevMgmt/ConsumableConfigDyn.xml", "localhost")
        assertEquals(
            "GET /DevMgmt/ConsumableConfigDyn.xml HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "User-Agent: hplip\r\n" +
                "Accept: text/xml\r\n" +
                "Accept-Language: en-us,en\r\n" +
                "Accept-Charset:utf-8\r\n" +
                "Keep-Alive: 20\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "Cookie: AccessCounter=new\r\n0\r\n\r\n",
            req,
        )
    }

    @Test
    fun `parses names colors percent and state from namespaced consumable XML`() {
        val xml = """
            <ccdyn:ConsumableConfigDyn xmlns:ccdyn="urn:hp">
              <ccdyn:ConsumableInfo>
                <dd:ConsumableLabelCode>black_cartridge</dd:ConsumableLabelCode>
                <dd:ConsumableColor>black</dd:ConsumableColor>
                <dd:ConsumableType>ink_cartridge</dd:ConsumableType>
                <dd:ConsumablePercentageLevelRemaining>63</dd:ConsumablePercentageLevelRemaining>
                <dd:ConsumableState>ok</dd:ConsumableState>
              </ccdyn:ConsumableInfo>
              <ccdyn:ConsumableInfo>
                <dd:ConsumableLabelCode>tri_color_cartridge</dd:ConsumableLabelCode>
                <dd:ConsumableColor>cyan magenta yellow</dd:ConsumableColor>
                <dd:ConsumableRawPercentageLevelRemaining>12</dd:ConsumableRawPercentageLevelRemaining>
                <dd:ConsumableStatusMessage>low ink</dd:ConsumableStatusMessage>
              </ccdyn:ConsumableInfo>
            </ccdyn:ConsumableConfigDyn>
        """.trimIndent()

        val status = LedmSupplyStatus.parseCartridges(xml)

        assertEquals(2, status.size)
        assertEquals("Black cartridge", status[0].name)
        assertEquals("Black", status[0].color)
        assertEquals("Ink cartridge", status[0].type)
        assertEquals(63, status[0].levelPercent)
        assertEquals("Ok", status[0].state)
        assertEquals("Tri color cartridge", status[1].name)
        assertEquals(12, status[1].levelPercent)
        assertEquals("Low ink", status[1].message)
    }

    @Test
    fun `fetch reads content-length supply XML`() {
        val xml = "<ConsumableInfo><ConsumableLabelCode>black</ConsumableLabelCode><ConsumablePercentageLevelRemaining>70</ConsumablePercentageLevelRemaining></ConsumableInfo>"
        val transport = FakePrinterTransport { req ->
            assertTrue(String(req).startsWith("GET /DevMgmt/ConsumableConfigDyn.xml HTTP/1.1"))
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/xml\r\n" +
                    "Content-Length: ${xml.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                    xml
            ).toByteArray(Charsets.UTF_8)
        }

        val fetched = LedmSupplyStatus.fetch({ transport }, "/DevMgmt/ConsumableConfigDyn.xml")

        assertEquals(xml, fetched)
        assertTrue(transport.closed)
    }

    @Test
    fun `query falls back to the next endpoint when the first one is missing`() {
        val successfulXml = "<ConsumableInfo><ConsumableLabelCode>black</ConsumableLabelCode><ConsumablePercentageLevelRemaining>55</ConsumablePercentageLevelRemaining></ConsumableInfo>"
        var opens = 0

        val result = LedmSupplyStatus.query(
            openTransport = {
                opens += 1
                FakePrinterTransport { req ->
                    val request = String(req)
                    if (request.startsWith("GET /missing.xml")) {
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()
                    } else {
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Transfer-Encoding: chunked\r\n\r\n" +
                                successfulXml.length.toString(16) + "\r\n" +
                                successfulXml + "\r\n0\r\n\r\n"
                        ).toByteArray()
                    }
                }
            },
            paths = listOf("/missing.xml", "/DevMgmt/ProductStatusDyn.xml"),
        )

        assertEquals(2, opens)
        assertEquals("/DevMgmt/ProductStatusDyn.xml", result.sourcePath)
        assertEquals(55, result.cartridges.single().levelPercent)
    }
}
