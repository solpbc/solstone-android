// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.ByteArrayOutputStream
import java.util.Locale

data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun bodyText(): String = body.toString(Charsets.UTF_8)
}

fun httpRequestBytes(method: String, path: String, contentType: String?, body: ByteArray?): ByteArray {
    val bodyBytes = body ?: ByteArray(0)
    val head = StringBuilder()
    head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
    head.append("host: spl.local\r\n")
    head.append("accept: application/json\r\n")
    if (contentType != null && contentType.isNotEmpty()) {
        head.append("content-type: ").append(contentType).append("\r\n")
    }
    head.append("content-length: ").append(bodyBytes.size).append("\r\n")
    head.append("\r\n")
    return head.toString().toByteArray(Charsets.US_ASCII) + bodyBytes
}

fun parseHttpResponse(raw: ByteArray): HttpResponse {
    val marker = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    val split = indexOf(raw, marker)
    if (split < 0) {
        throw IllegalStateException("HTTP response missing header terminator")
    }
    val head = String(raw, 0, split, Charsets.ISO_8859_1)
    val lines = head.split("\r\n")
    if (lines.isEmpty()) {
        throw IllegalStateException("HTTP response missing status line")
    }
    val statusParts = lines[0].split(" ", limit = 3)
    if (statusParts.size < 2) {
        throw IllegalStateException("bad HTTP status line: ${lines[0]}")
    }
    val status = statusParts[1].toInt()
    val headers = LinkedHashMap<String, String>()
    for (index in 1 until lines.size) {
        val colon = lines[index].indexOf(':')
        if (colon <= 0) {
            continue
        }
        headers[lines[index].substring(0, colon).trim().lowercase(Locale.US)] =
            lines[index].substring(colon + 1).trim()
    }
    var body = raw.copyOfRange(split + marker.size, raw.size)
    if (headers["transfer-encoding"].equals("chunked", ignoreCase = true)) {
        body = dechunk(body)
    } else if (headers.containsKey("content-length")) {
        val length = headers.getValue("content-length").toInt()
        if (length < body.size) {
            body = body.copyOf(length)
        }
    }
    return HttpResponse(status, headers, body)
}

fun dechunk(raw: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var index = 0
    while (index < raw.size) {
        val lineEnd = indexOf(raw, "\r\n".toByteArray(Charsets.US_ASCII), index)
        if (lineEnd < 0) {
            throw IllegalStateException("chunked body missing size line")
        }
        val sizeText = String(raw, index, lineEnd - index, Charsets.US_ASCII)
            .split(";", limit = 2)[0]
            .trim()
        val size = sizeText.toInt(16)
        index = lineEnd + 2
        if (size == 0) {
            return out.toByteArray()
        }
        if (index + size > raw.size) {
            throw IllegalStateException("chunked body truncated")
        }
        out.write(raw, index, size)
        index += size + 2
    }
    return out.toByteArray()
}

fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int = 0): Int {
    var i = maxOf(0, start)
    while (i <= haystack.size - needle.size) {
        var matched = true
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                matched = false
                break
            }
        }
        if (matched) {
            return i
        }
        i += 1
    }
    return -1
}
