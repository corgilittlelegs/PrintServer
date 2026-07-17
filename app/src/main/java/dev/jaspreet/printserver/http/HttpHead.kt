package dev.jaspreet.printserver.http

import java.io.IOException
import java.io.InputStream

/** Parsed HTTP request or response head (start line + headers, no body). */
class HttpHead(val startLine: String, headers: List<Pair<String, String>>) {
    private val headers = headers.toMutableList()

    fun get(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** Returns all header values for [name] (case-insensitive), in order, for detecting duplicates. */
    fun getAll(name: String): List<String> =
        headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    fun set(name: String, value: String) {
        require(!name.contains('\r') && !name.contains('\n')) { "Header name contains CR/LF: $name" }
        require(!value.contains('\r') && !value.contains('\n')) { "Header value contains CR/LF: $value" }
        headers.removeAll { it.first.equals(name, ignoreCase = true) }
        headers.add(name to value)
    }

    fun remove(name: String) {
        headers.removeAll { it.first.equals(name, ignoreCase = true) }
    }

    fun serialize(): ByteArray = buildString {
        append(startLine).append("\r\n")
        headers.forEach { (n, v) -> append(n).append(": ").append(v).append("\r\n") }
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    companion object {
        /** Maximum bytes allowed in a single start-line or header line before parsing fails. */
        private const val MAX_LINE_LENGTH = 8192

        /** Maximum number of headers accepted before a blank line. */
        private const val MAX_HEADER_COUNT = 100

        /** Returns null if the stream is at EOF before any byte of the start line. */
        fun parse(input: InputStream): HttpHead? {
            val start = readLine(input) ?: return null
            val list = mutableListOf<Pair<String, String>>()
            while (true) {
                val line = readLine(input) ?: throw IOException("EOF inside HTTP headers")
                if (line.isEmpty()) break
                if (list.size >= MAX_HEADER_COUNT) throw IOException("Too many headers (max $MAX_HEADER_COUNT)")
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
                if (c != '\r'.code) {
                    if (sb.length >= MAX_LINE_LENGTH) throw IOException("Line exceeds max length ($MAX_LINE_LENGTH)")
                    sb.append(c.toChar())
                }
            }
        }
    }
}
