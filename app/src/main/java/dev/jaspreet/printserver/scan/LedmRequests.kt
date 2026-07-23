package dev.jaspreet.printserver.scan

/**
 * Builds the exact HTTP/1.1 request text for HP's LEDM scan protocol. These requests
 * travel as raw bytes over a bulk USB pipe, not a real socket -- see
 * docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md for the wire-format
 * source (HPLIP 3.24.4's scan/sane/bb_ledm.c).
 */
object LedmRequests {
    /** Standard HTTP/1.1 chunked-transfer-encoding zero-length terminator chunk. */
    const val ZERO_FOOTER = "\r\n0\r\n\r\n"

    fun statusRequest(host: String): String =
        "GET /Scan/Status HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: hplip\r\n" +
            "Accept: text/xml\r\n" +
            "Accept-Language: en-us,en\r\n" +
            "Accept-Charset:utf-8\r\n" +
            "Keep-Alive: 20\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Cookie: AccessCounter=new" +
            ZERO_FOOTER

    fun scanCapsRequest(host: String): String =
        "GET /Scan/ScanCaps HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: hplip\r\n" +
            "Accept: text/xml\r\n" +
            "Accept-Language: en-us,en\r\n" +
            "Accept-Charset:utf-8\r\n" +
            "Keep-Alive: 20\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Cookie: AccessCounter=new" +
            ZERO_FOOTER

    /** [contentLength] must be the create-job XML body's byte length plus [ZERO_FOOTER]'s
     *  byte length -- this mirrors bb_ledm.c's own (unusual, but firmware-required)
     *  framing exactly: a real Content-Length header alongside a chunked-style zero
     *  footer written as a separate trailing write. */
    fun createJobHeader(host: String, contentLength: Int): String =
        "POST /Scan/Jobs HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: hplip\r\n" +
            "Accept: text/plain, */*\r\n" +
            "Accept-Language: en-us,en\r\n" +
            "Accept-Charset: ISO-8859-1,utf-8\r\n" +
            "Keep-Alive: 1000\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Content-Type: */*; charset=UTF-8\r\n" +
            "X-Requested-With: XMLHttpRequest\r\n" +
            "Content-Length: $contentLength\r\n" +
            "Cookie: AccessCounter=new\r\n" +
            "Pragma: no-cache\r\n" +
            "Cache-Control: no-cache\r\n\r\n"

    fun createJobBody(
        xResolution: Int,
        yResolution: Int,
        xStart: Int,
        width: Int,
        yStart: Int,
        height: Int,
        colorSpace: String,
        brightness: Int = ScanTone.DEFAULT,
        contrast: Int = ScanTone.DEFAULT,
    ): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<ScanSettings xmlns=\"http://www.hp.com/schemas/imaging/con/cnx/scan/2008/08/19\">" +
            "<XResolution>$xResolution</XResolution>" +
            "<YResolution>$yResolution</YResolution>" +
            "<XStart>$xStart</XStart>" +
            "<Width>$width</Width>" +
            "<YStart>$yStart</YStart>" +
            "<Height>$height</Height>" +
            "<Format>Jpeg</Format>" +
            "<CompressionQFactor>15</CompressionQFactor>" +
            "<ColorSpace>$colorSpace</ColorSpace>" +
            "<BitDepth>8</BitDepth>" +
            "<InputSource>Platen</InputSource>" +
            "<InputSourceType>Platen</InputSourceType>" +
            "<GrayRendering>NTSC</GrayRendering>" +
            "<ToneMap><Gamma>0</Gamma><Brightness>$brightness</Brightness><Contrast>$contrast</Contrast>" +
            "<Highlite>0</Highlite><Shadow>0</Shadow></ToneMap>" +
            "<ContentType>Photo</ContentType></ScanSettings>"

    /** Used both to poll a job's status (path = the Location header's value from the
     *  create-job response) and to fetch the final image (path = the parsed
     *  &lt;BinaryURL&gt; value) -- bb_ledm.c uses the identical request template for both. */
    fun getResourceRequest(path: String, host: String): String =
        "GET $path HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "User-Agent: hplip\r\n" +
            "Accept: text/plain\r\n" +
            "Accept-Language: en-us,en\r\n" +
            "Accept-Charset:utf-8\r\n" +
            "X-Requested-With: XMLHttpRequest\r\n" +
            "Keep-Alive: 300\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Cookie: AccessCounter=new" +
            ZERO_FOOTER
}
