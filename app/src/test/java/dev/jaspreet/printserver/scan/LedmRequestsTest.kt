package dev.jaspreet.printserver.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedmRequestsTest {

    @Test
    fun `builds the scanner status request`() {
        val req = LedmRequests.statusRequest("localhost")
        assertEquals(
            "GET /Scan/Status HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "User-Agent: hplip\r\n" +
                "Accept: text/xml\r\n" +
                "Accept-Language: en-us,en\r\n" +
                "Accept-Charset:utf-8\r\n" +
                "Keep-Alive: 20\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "Cookie: AccessCounter=new\r\n" +
                "0\r\n\r\n",
            req,
        )
    }

    @Test
    fun `builds the scan capabilities request`() {
        val req = LedmRequests.scanCapsRequest("localhost")
        assertEquals(
            "GET /Scan/ScanCaps HTTP/1.1\r\n" +
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
    fun `builds the create-job request header with the given content length`() {
        val req = LedmRequests.createJobHeader("localhost", 321)
        assertTrue(req.startsWith("POST /Scan/Jobs HTTP/1.1\r\n"))
        assertTrue(req.contains("Content-Length: 321\r\n"))
        assertTrue(req.contains("Host: localhost\r\n"))
        assertTrue(req.endsWith("Cache-Control: no-cache\r\n\r\n"))
    }

    @Test
    fun `builds the create-job XML body with the given scan settings`() {
        val body = LedmRequests.createJobBody(
            xResolution = 300, yResolution = 300,
            xStart = 0, width = 2550, yStart = 0, height = 3300,
            colorSpace = "Color",
        )
        assertTrue(body.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(body.contains("<XResolution>300</XResolution>"))
        assertTrue(body.contains("<YResolution>300</YResolution>"))
        assertTrue(body.contains("<XStart>0</XStart>"))
        assertTrue(body.contains("<Width>2550</Width>"))
        assertTrue(body.contains("<YStart>0</YStart>"))
        assertTrue(body.contains("<Height>3300</Height>"))
        assertTrue(body.contains("<Format>Jpeg</Format>"))
        assertTrue(body.contains("<ColorSpace>Color</ColorSpace>"))
        assertTrue(body.contains("<Brightness>1000</Brightness>"))
        assertTrue(body.contains("<Contrast>1000</Contrast>"))
        assertTrue(body.contains("<InputSource>Platen</InputSource>"))
        assertTrue(body.endsWith("</ScanSettings>"))
    }

    @Test
    fun `builds the create-job XML body with grayscale color space`() {
        val body = LedmRequests.createJobBody(
            xResolution = 300, yResolution = 300,
            xStart = 0, width = 2550, yStart = 0, height = 3300,
            colorSpace = "Gray",
        )
        assertTrue(body.contains("<ColorSpace>Gray</ColorSpace>"))
    }

    @Test
    fun `builds a GET request for an arbitrary job resource path`() {
        val req = LedmRequests.getResourceRequest("/Scan/Jobs/JobList/1", "localhost")
        assertEquals(
            "GET /Scan/Jobs/JobList/1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "User-Agent: hplip\r\n" +
                "Accept: text/plain\r\n" +
                "Accept-Language: en-us,en\r\n" +
                "Accept-Charset:utf-8\r\n" +
                "X-Requested-With: XMLHttpRequest\r\n" +
                "Keep-Alive: 300\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "Cookie: AccessCounter=new\r\n" +
                "0\r\n\r\n",
            req,
        )
    }

    @Test
    fun `the zero footer is the standard chunked terminator`() {
        assertEquals("\r\n0\r\n\r\n", LedmRequests.ZERO_FOOTER)
    }
}
