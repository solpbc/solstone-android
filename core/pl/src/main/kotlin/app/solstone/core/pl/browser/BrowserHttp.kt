// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.Locale

data class BrowserHttpResponse(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
) {
    fun bodyText(): String = body.toString(Charsets.UTF_8)

    fun header(name: String): String? {
        val target = name.lowercase(Locale.US)
        return headers.firstOrNull { it.first.lowercase(Locale.US) == target }?.second
    }

    fun headers(name: String): List<String> {
        val target = name.lowercase(Locale.US)
        return headers.filter { it.first.lowercase(Locale.US) == target }.map { it.second }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BrowserHttpResponse) return false
        if (status != other.status) return false
        if (headers != other.headers) return false
        if (!body.contentEquals(other.body)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

val HOP_BY_HOP_HEADERS: Set<String> = setOf(
    "connection",
    "keep-alive",
    "proxy-connection",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "host",
    "content-length",
)

val REQUEST_ALLOWED_HEADERS: Set<String> = setOf(
    "accept",
    "accept-language",
    "accept-encoding",
    "content-type",
    "cookie",
    "range",
    "if-range",
    "if-match",
    "if-none-match",
    "if-modified-since",
    "if-unmodified-since",
    "cache-control",
    "pragma",
    "user-agent",
)

val RESPONSE_ALLOWED_HEADERS: Set<String> = setOf(
    "content-type",
    "content-encoding",
    "content-range",
    "accept-ranges",
    "etag",
    "last-modified",
    "cache-control",
    "expires",
    "pragma",
    "vary",
    "location",
    "set-cookie",
    "www-authenticate",
    "date",
)

fun parseBrowserHttpResponse(raw: ByteArray): BrowserHttpResponse {
    val headerEnd = findHeaderEnd(raw)
    if (headerEnd < 0) {
        throw IOException("HTTP response missing header delimiter")
    }
    val headerText = raw.copyOfRange(0, headerEnd).toString(Charsets.UTF_8)
    val lines = headerText.split(Regex("\r?\n"))
    if (lines.isEmpty() || lines[0].isBlank()) {
        throw IOException("Empty HTTP status line")
    }
    val statusParts = lines[0].trim().split(" ", limit = 3)
    if (statusParts.size < 2) {
        throw IOException("Malformed HTTP status line: ${lines[0]}")
    }
    val status = statusParts[1].toIntOrNull()
        ?: throw IOException("Malformed HTTP status code: ${statusParts[1]}")

    val parsedHeaders = mutableListOf<Pair<String, String>>()
    for (i in 1 until lines.size) {
        val line = lines[i]
        if (line.isBlank()) continue
        val colon = line.indexOf(':')
        if (colon < 0) {
            throw IOException("Malformed HTTP header line: $line")
        }
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        parsedHeaders.add(name to value)
    }

    val bodyOffset = headerEnd
    val remainingBytes = raw.size - bodyOffset

    val transferEncoding = parsedHeaders.firstOrNull {
        it.first.equals("transfer-encoding", ignoreCase = true)
    }?.second?.lowercase(Locale.US)
    val contentLengthHeader = parsedHeaders.firstOrNull {
        it.first.equals("content-length", ignoreCase = true)
    }?.second

    val body: ByteArray
    if (transferEncoding?.contains("chunked") == true) {
        body = dechunkBrowser(raw, bodyOffset)
    } else if (contentLengthHeader != null) {
        val length = contentLengthHeader.toIntOrNull()
            ?: throw IOException("Malformed Content-Length: $contentLengthHeader")
        if (length < 0) {
            throw IOException("Negative Content-Length: $length")
        }
        if (remainingBytes != length) {
            throw IOException("Framing mismatch: Content-Length is $length but payload has $remainingBytes bytes")
        }
        body = raw.copyOfRange(bodyOffset, bodyOffset + length)
    } else {
        // Status codes 1xx, 204, 304 have no body by specification
        if (status in 100..199 || status == 204 || status == 304) {
            if (remainingBytes > 0) {
                throw IOException("Unexpected body bytes for status $status")
            }
            body = ByteArray(0)
        } else {
            throw IOException("Response missing both Content-Length and Transfer-Encoding")
        }
    }

    return BrowserHttpResponse(
        status = status,
        headers = parsedHeaders,
        body = body,
    )
}

private fun findHeaderEnd(raw: ByteArray): Int {
    for (i in 0 until raw.size - 3) {
        if (raw[i] == '\r'.code.toByte() &&
            raw[i + 1] == '\n'.code.toByte() &&
            raw[i + 2] == '\r'.code.toByte() &&
            raw[i + 3] == '\n'.code.toByte()
        ) {
            return i + 4
        }
    }
    for (i in 0 until raw.size - 1) {
        if (raw[i] == '\n'.code.toByte() && raw[i + 1] == '\n'.code.toByte()) {
            return i + 2
        }
    }
    return -1
}

private fun dechunkBrowser(raw: ByteArray, startOffset: Int): ByteArray {
    val out = ByteArrayOutputStream()
    var offset = startOffset
    while (offset < raw.size) {
        val lineEnd = findCrLf(raw, offset)
        if (lineEnd < 0) {
            throw IOException("Chunk header missing CRLF")
        }
        val line = raw.copyOfRange(offset, lineEnd).toString(Charsets.US_ASCII).trim()
        val chunkLenHex = line.substringBefore(';').trim()
        val chunkLen = chunkLenHex.toIntOrNull(16)
            ?: throw IOException("Invalid chunk length hex: $chunkLenHex")
        offset = lineEnd + 2
        if (chunkLen == 0) {
            // Terminator chunk: trailer headers or final CRLF
            val trailerEnd = findDoubleCrLfOrSingle(raw, offset)
            if (trailerEnd < 0) {
                // If it ends right at CRLF
                if (offset + 2 <= raw.size && raw[offset] == '\r'.code.toByte() && raw[offset + 1] == '\n'.code.toByte()) {
                    offset += 2
                } else if (offset == raw.size) {
                    // Terminated
                } else {
                    throw IOException("Chunked stream missing trailing CRLF")
                }
            } else {
                offset = trailerEnd
            }
            if (offset != raw.size) {
                throw IOException("Unconsumed trailing bytes after chunked terminator: ${raw.size - offset} bytes")
            }
            return out.toByteArray()
        }
        if (offset + chunkLen > raw.size) {
            throw IOException("Incomplete chunk data: expected $chunkLen bytes, only ${raw.size - offset} remaining")
        }
        out.write(raw, offset, chunkLen)
        offset += chunkLen
        if (offset + 2 > raw.size || raw[offset] != '\r'.code.toByte() || raw[offset + 1] != '\n'.code.toByte()) {
            throw IOException("Chunk data missing trailing CRLF")
        }
        offset += 2
    }
    throw IOException("Truncated chunked response without 0-terminator")
}

private fun findCrLf(raw: ByteArray, start: Int): Int {
    for (i in start until raw.size - 1) {
        if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte()) {
            return i
        }
    }
    return -1
}

private fun findDoubleCrLfOrSingle(raw: ByteArray, start: Int): Int {
    if (start + 2 <= raw.size && raw[start] == '\r'.code.toByte() && raw[start + 1] == '\n'.code.toByte()) {
        return start + 2
    }
    for (i in start until raw.size - 3) {
        if (raw[i] == '\r'.code.toByte() &&
            raw[i + 1] == '\n'.code.toByte() &&
            raw[i + 2] == '\r'.code.toByte() &&
            raw[i + 3] == '\n'.code.toByte()
        ) {
            return i + 4
        }
    }
    return -1
}

fun filterRequestHeaders(rawHeaders: List<Pair<String, String>>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val cookies = mutableListOf<String>()
    for ((name, value) in rawHeaders) {
        val lower = name.lowercase(Locale.US)
        if (lower in HOP_BY_HOP_HEADERS) continue
        if (lower == "cookie") {
            if (value.isNotBlank()) {
                cookies.add(value.trim())
            }
            continue
        }
        if (lower in REQUEST_ALLOWED_HEADERS) {
            result[lower] = value
        }
    }
    if (cookies.isNotEmpty()) {
        result["cookie"] = cookies.joinToString("; ")
    }
    return result
}

fun stripCookieDomain(cookieHeaderValue: String): String {
    val parts = cookieHeaderValue.split(';')
    val remaining = mutableListOf<String>()
    for (part in parts) {
        val trimmed = part.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.lowercase(Locale.US).startsWith("domain=")) {
            continue
        }
        remaining.add(trimmed)
    }
    return remaining.joinToString("; ")
}

fun filterAndFormatResponseHeaders(
    upstreamHeaders: List<Pair<String, String>>,
    responseBodySize: Int,
    localOriginUrl: String,
    statusCode: Int,
): List<Pair<String, String>>? {
    val out = mutableListOf<Pair<String, String>>()
    var hasLocation = false
    var locationRebased: String? = null

    for ((name, value) in upstreamHeaders) {
        val lower = name.lowercase(Locale.US)
        if (lower in HOP_BY_HOP_HEADERS) continue
        if (lower !in RESPONSE_ALLOWED_HEADERS) continue

        when (lower) {
            "set-cookie" -> {
                val stripped = stripCookieDomain(value)
                out.add("Set-Cookie" to stripped)
            }
            "location" -> {
                hasLocation = true
                locationRebased = rebaseRedirectLocation(value, localOriginUrl)
                if (locationRebased == null) {
                    // Invalid/foreign redirect -> reject entirely (signals 502)
                    return null
                }
                out.add("Location" to locationRebased)
            }
            "content-type" -> out.add("Content-Type" to value)
            "content-encoding" -> out.add("Content-Encoding" to value)
            "content-range" -> out.add("Content-Range" to value)
            "accept-ranges" -> out.add("Accept-Ranges" to value)
            "etag" -> out.add("ETag" to value)
            "last-modified" -> out.add("Last-Modified" to value)
            "cache-control" -> out.add("Cache-Control" to value)
            "expires" -> out.add("Expires" to value)
            "pragma" -> out.add("Pragma" to value)
            "vary" -> out.add("Vary" to value)
            "www-authenticate" -> out.add("WWW-Authenticate" to value)
            "date" -> out.add("Date" to value)
        }
    }

    if (statusCode in 301..308 && statusCode != 304 && !hasLocation) {
        // Missing location on redirect requiring Location -> reject
        return null
    }

    out.add("Referrer-Policy" to "no-referrer")
    out.add("Content-Length" to responseBodySize.toString())
    return out
}

fun rebaseRedirectLocation(location: String, localOriginUrl: String): String? {
    val trimmed = location.trim()
    if (trimmed.isEmpty()) return null

    // Disallow dangerous URI schemes
    val lower = trimmed.lowercase(Locale.US)
    if (lower.startsWith("javascript:") ||
        lower.startsWith("data:") ||
        lower.startsWith("file:") ||
        lower.startsWith("blob:") ||
        lower.startsWith("about:")
    ) {
        return null
    }

    val localUri = try {
        URI(localOriginUrl)
    } catch (_: Exception) {
        return null
    }
    val localHost = localUri.host?.lowercase(Locale.US) ?: return null
    val localPort = localUri.port

    val parsed = try {
        URI(trimmed)
    } catch (_: Exception) {
        return null
    }

    if (parsed.userInfo != null || trimmed.contains("@")) {
        return null
    }

    val scheme = parsed.scheme?.lowercase(Locale.US)
    val host = parsed.host?.lowercase(Locale.US)
    val port = parsed.port

    if (scheme != null && scheme != "http" && scheme != "https") {
        return null
    }

    if (host != null) {
        val isSplLocal = host == "spl.local" && (port == -1 || port == 80 || port == 443)
        val isLocalOrigin = host == localHost && (port == -1 || port == localPort)
        if (!isSplLocal && !isLocalOrigin) {
            return null
        }
    }

    val path = parsed.rawPath ?: ""
    val query = parsed.rawQuery
    val fragment = parsed.rawFragment

    val localOriginNoSlash = localOriginUrl.removeSuffix("/")
    val finalPath = when {
        path.startsWith("/") -> path
        path.isEmpty() -> if (query != null || fragment != null) "/" else "/"
        else -> "/$path"
    }

    val sb = StringBuilder()
    sb.append(localOriginNoSlash)
    sb.append(finalPath)
    if (query != null) {
        sb.append("?").append(query)
    }
    if (fragment != null) {
        sb.append("#").append(fragment)
    }
    return sb.toString()
}
