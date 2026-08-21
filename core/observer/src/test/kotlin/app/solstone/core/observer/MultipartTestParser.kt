// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

data class ParsedMultipartPart(
    val name: String,
    val filename: String?,
    val contentType: String?,
    val body: ByteArray,
)

fun parseMultipart(body: ByteArray, boundary: String): List<ParsedMultipartPart> {
    val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
    val separator = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    val nextPart = "\r\n--$boundary".toByteArray(Charsets.US_ASCII)
    var position = 0

    fun consumeBoundary(): Boolean {
        require(body.matchesAt(position, delimiter)) { "missing multipart boundary" }
        position += delimiter.size
        if (body.matchesAt(position, "--".toByteArray(Charsets.US_ASCII))) {
            position += 2
            require(body.matchesAt(position, "\r\n".toByteArray(Charsets.US_ASCII))) { "unterminated multipart body" }
            position += 2
            return false
        }
        require(body.matchesAt(position, "\r\n".toByteArray(Charsets.US_ASCII))) { "invalid multipart boundary" }
        position += 2
        return true
    }

    val parts = mutableListOf<ParsedMultipartPart>()
    while (consumeBoundary()) {
        val headerEnd = body.indexOf(separator, position)
        require(headerEnd >= 0) { "multipart headers missing terminator" }
        val headers = body.copyOfRange(position, headerEnd).toString(Charsets.UTF_8)
            .lineSequence()
            .associate { line ->
                val separatorIndex = line.indexOf(':')
                require(separatorIndex > 0) { "invalid multipart header" }
                line.substring(0, separatorIndex).lowercase() to line.substring(separatorIndex + 1).trim()
            }
        position = headerEnd + separator.size
        val bodyEnd = body.indexOf(nextPart, position)
        require(bodyEnd >= 0) { "multipart part missing next boundary" }
        val disposition = requireNotNull(headers["content-disposition"]) { "missing content disposition" }
        val parameters = parseDisposition(disposition)
        parts += ParsedMultipartPart(
            name = requireNotNull(parameters["name"]) { "missing multipart part name" },
            filename = parameters["filename"],
            contentType = headers["content-type"],
            body = body.copyOfRange(position, bodyEnd),
        )
        position = bodyEnd + 2
    }
    require(position == body.size) { "trailing multipart content" }
    return parts
}

private fun parseDisposition(value: String): Map<String, String> {
    require(value.startsWith("form-data")) { "unexpected content disposition" }
    val parameters = linkedMapOf<String, String>()
    var index = "form-data".length
    while (index < value.length) {
        require(value[index] == ';') { "invalid content disposition" }
        index += 1
        while (index < value.length && value[index] == ' ') index += 1
        val equals = value.indexOf('=', index)
        require(equals > index) { "invalid content disposition parameter" }
        val key = value.substring(index, equals)
        index = equals + 1
        require(index < value.length && value[index] == '"') { "expected quoted disposition value" }
        index += 1
        val parsed = StringBuilder()
        while (index < value.length && value[index] != '"') {
            if (value[index] == '\\') {
                index += 1
                require(index < value.length) { "unterminated disposition escape" }
            }
            parsed.append(value[index++])
        }
        require(index < value.length) { "unterminated disposition value" }
        index += 1
        parameters[key] = parsed.toString()
    }
    return parameters
}

private fun ByteArray.matchesAt(offset: Int, needle: ByteArray): Boolean =
    offset >= 0 && offset + needle.size <= size && needle.indices.all { this[offset + it] == needle[it] }

private fun ByteArray.indexOf(needle: ByteArray, startIndex: Int): Int {
    for (index in startIndex..size - needle.size) {
        if (matchesAt(index, needle)) return index
    }
    return -1
}
