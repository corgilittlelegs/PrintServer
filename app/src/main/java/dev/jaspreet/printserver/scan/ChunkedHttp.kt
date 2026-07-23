package dev.jaspreet.printserver.scan

import java.io.ByteArrayOutputStream

/** Decodes an HTTP/1.1 response's header and chunked-transfer-encoded body (RFC 7230
 *  §4.1) from a [PullReader]. See docs/superpowers/specs/2026-07-19-native-scan-pipeline-design.md
 *  for why LEDM responses are genuinely framed this way. */
object ChunkedHttp {

    /** Reads the status line and headers up to (not including) the blank line. */
    fun readHeader(reader: PullReader): String {
        val header = StringBuilder()
        var skippedLines = 0
        while (true) {
            val line = reader.readLine()
            if (line.startsWith("HTTP/1.1")) {
                header.append(line).append("\r\n")
                break
            }
            if (++skippedLines > MAX_HEADER_SYNC_LINES) {
                throw java.io.IOException("HTTP header not found")
            }
        }
        while (true) {
            val line = reader.readLine()
            if (line.isEmpty()) break
            header.append(line).append("\r\n")
        }
        return header.toString()
    }

    fun isCreated(header: String): Boolean =
        header.lineSequence().firstOrNull()?.startsWith("HTTP/1.1 201 ") == true

    /** Reads a chunked body: repeated (hex-size line, that many bytes, trailing CRLF),
     *  terminated by a zero-size chunk. */
    fun readChunkedBody(reader: PullReader): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = reader.readLine().trim().toInt(16)
            if (size == 0) {
                reader.readLine() // trailing blank line after the zero chunk
                break
            }
            out.write(reader.readExactly(size))
            reader.readLine() // trailing CRLF after this chunk's data
        }
        return out.toByteArray()
    }

    private const val MAX_HEADER_SYNC_LINES = 32
}
