package dev.jaspreet.printserver.http

import java.io.IOException

internal sealed class HttpBodyFraming {
    data object Empty : HttpBodyFraming()
    data class ContentLength(val length: Long) : HttpBodyFraming()
    data object Chunked : HttpBodyFraming()
}

internal fun HttpHead.bodyFraming(): HttpBodyFraming {
    val transferEncodings = getAll("Transfer-Encoding")
    val contentLengths = getAll("Content-Length")

    if (transferEncodings.isNotEmpty()) {
        if (contentLengths.isNotEmpty()) throw IOException("Content-Length is not allowed with Transfer-Encoding")
        val codings = transferEncodings.flatMap { value ->
            value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        }
        if (codings != listOf("chunked")) throw IOException("Unsupported Transfer-Encoding: ${transferEncodings.joinToString(",")}")
        return HttpBodyFraming.Chunked
    }

    if (contentLengths.isEmpty()) return HttpBodyFraming.Empty
    if (contentLengths.size != 1) throw IOException("Duplicate Content-Length headers")

    val raw = contentLengths.single().trim()
    if (raw.isEmpty() || raw.any { it !in '0'..'9' }) throw IOException("Invalid Content-Length: $raw")
    return HttpBodyFraming.ContentLength(raw.toLongOrNull() ?: throw IOException("Content-Length is too large: $raw"))
}
