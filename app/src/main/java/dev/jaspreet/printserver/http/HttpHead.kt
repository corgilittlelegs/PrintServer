package dev.jaspreet.printserver.http

import java.io.IOException
import java.io.InputStream

/** Parsed HTTP request or response head (start line + headers, no body). */
class HttpHead(val startLine: String, headers: List<Pair<String, String>>) {
    private val headers = headers.toMutableList()

    fun get(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun set(name: String, value: String) {
        headers.removeAll { it.first.equals(name, ignoreCase = true) }
        headers.add(name to value)
    }

    fun serialize(): ByteArray = buildString {
        append(startLine).append("\r\n")
        headers.forEach { (n, v) -> append(n).append(": ").append(v).append("\r\n") }
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    companion object {
        /** Returns null if the stream is at EOF before any byte of the start line. */
        fun parse(input: InputStream): HttpHead? {
            val start = readLine(input) ?: return null
            val list = mutableListOf<Pair<String, String>>()
            while (true) {
                val line = readLine(input) ?: throw IOException("EOF inside HTTP headers")
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) throw IOException("Malformed header: $line")
                list += line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            return HttpHead(start, list)
        }

        /** Reads one CRLF- (or LF-) terminated line; null on EOF at line start. */
        fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString()
                if (c != '\r'.code) sb.append(c.toChar())
            }
        }
    }
}
