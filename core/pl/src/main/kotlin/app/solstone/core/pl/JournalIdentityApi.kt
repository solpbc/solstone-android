// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon

data class IdentityApiResponse(
    val committed: Boolean,
    val instanceId: String?,
    val mark: JournalMark?,
)

sealed interface IdentityGetResult {
    data class Success(val response: IdentityApiResponse) : IdentityGetResult
    data object NotFound : IdentityGetResult
    data class Failure(val status: Int?, val message: String?) : IdentityGetResult
}

private val HEX_COLOR_REGEX = Regex("^#[0-9a-f]{6}$")
private val WORD_REGEX = Regex("^[a-z]+$")
private val ALLOWED_SVG_ELEMENTS = setOf("path", "circle", "rect", "ellipse", "line", "polyline", "polygon")
private val PATH_D_REGEX = Regex("""\bd\s*=\s*["']([^"']*)["']""")

fun validateHexColor(hex: String): Boolean = HEX_COLOR_REGEX.matches(hex)

fun validateSvgPathD(d: String): Boolean {
    val text = d.trim()
    if (text.isEmpty()) return false

    var index = 0
    var currentCommand: Char? = null
    var numberCountForCommand = 0
    var requiredParams = 0

    fun checkCommandComplete(): Boolean {
        val cmd = currentCommand ?: return true
        if (requiredParams == 0) {
            return numberCountForCommand == 0
        }
        return numberCountForCommand > 0 && (numberCountForCommand % requiredParams == 0)
    }

    while (index < text.length) {
        val c = text[index]
        if (c.isWhitespace() || c == ',') {
            index++
            continue
        }

        if (c in "MmLlHhVvCcSsQqTtAaZz") {
            if (!checkCommandComplete()) return false
            currentCommand = c
            numberCountForCommand = 0
            requiredParams = when (c) {
                'M', 'm', 'L', 'l', 'T', 't' -> 2
                'H', 'h', 'V', 'v' -> 1
                'C', 'c' -> 6
                'S', 's', 'Q', 'q' -> 4
                'A', 'a' -> 7
                'Z', 'z' -> 0
                else -> return false
            }
            index++
            continue
        }

        // Must be parsing a number for the current command
        if (currentCommand == null || requiredParams == 0) return false

        // Parse a number
        val start = index
        if (text[index] == '+' || text[index] == '-') {
            index++
            if (index >= text.length) return false
        }

        var hasDigits = false
        while (index < text.length && text[index].isDigit()) {
            hasDigits = true
            index++
        }

        if (index < text.length && text[index] == '.') {
            index++
            while (index < text.length && text[index].isDigit()) {
                hasDigits = true
                index++
            }
        }

        if (!hasDigits) return false

        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) {
                index++
            }
            var expDigits = false
            while (index < text.length && text[index].isDigit()) {
                expDigits = true
                index++
            }
            if (!expDigits) return false
        }

        val numStr = text.substring(start, index)
        if (numStr.toDoubleOrNull() == null) return false
        numberCountForCommand++
    }

    if (!checkCommandComplete()) return false
    return currentCommand != null
}

fun validateMarkSvg(svg: String): Boolean {
    val trimmed = svg.trim()
    if (trimmed.isEmpty()) return false
    var i = 0
    var elementCount = 0
    while (i < trimmed.length) {
        val openIndex = trimmed.indexOf('<', i)
        if (openIndex == -1) break
        val closeIndex = trimmed.indexOf('>', openIndex)
        if (closeIndex == -1) return false
        val tagContent = trimmed.substring(openIndex + 1, closeIndex).trim()
        i = closeIndex + 1

        if (tagContent.startsWith("/")) {
            // Closing tag
            val tagName = tagContent.substring(1).trim()
            if (tagName !in ALLOWED_SVG_ELEMENTS) return false
        } else {
            // Opening or self-closing tag
            val tagWithoutSlash = tagContent.removeSuffix("/").trim()
            val tagName = tagWithoutSlash.split("\\s+".toRegex(), limit = 2)[0]
            if (tagName !in ALLOWED_SVG_ELEMENTS) return false
            if (tagName == "path") {
                val dMatch = PATH_D_REGEX.find(tagWithoutSlash) ?: return false
                val dVal = dMatch.groupValues[1]
                if (!validateSvgPathD(dVal)) return false
            }
            elementCount++
        }
    }
    return elementCount > 0
}

private fun parseIcon(map: Map<*, *>): JournalMarkIcon? {
    val name = map["name"] as? String ?: return null
    if (name.isBlank() || name.any { it in '\u0000'..'\u001F' || it == '\u007F' }) return null

    val svg = map["svg"] as? String ?: return null
    if (!validateMarkSvg(svg)) return null

    val colorMap = map["color"] as? Map<*, *> ?: return null
    val colorName = colorMap["name"] as? String ?: return null
    if (colorName.isBlank() || colorName.any { it in '\u0000'..'\u001F' || it == '\u007F' }) return null

    val colorHex = colorMap["hex"] as? String ?: return null
    if (!validateHexColor(colorHex)) return null

    val rot = (map["rot"] as? Number)?.toInt() ?: return null
    if (rot != 0 && rot != 45) return null

    return JournalMarkIcon(
        name = name,
        svg = svg,
        colorName = colorName,
        colorHex = colorHex,
        rot = rot,
    )
}

fun parseIdentityResponse(bodyText: String): IdentityApiResponse? {
    val root = runCatching { parseJson(bodyText) as? Map<*, *> }.getOrNull() ?: return null
    val committed = root["committed"] as? Boolean ?: return null

    if (!committed) {
        val mark = root["mark"]
        if (mark != null) return null
        val instanceId = root["instance_id"] as? String?
        return IdentityApiResponse(committed = false, instanceId = instanceId, mark = null)
    }

    val instanceId = root["instance_id"] as? String ?: return null
    if (instanceId.isBlank() || instanceId.any { it in '\u0000'..'\u001F' || it == '\u007F' }) return null

    val markMap = root["mark"] as? Map<*, *> ?: return null
    val icon1Map = markMap["icon1"] as? Map<*, *> ?: return null
    val icon2Map = markMap["icon2"] as? Map<*, *> ?: return null
    val wordsList = markMap["words"] as? List<*> ?: return null

    if (wordsList.size != 2) return null
    val word1 = wordsList[0] as? String ?: return null
    val word2 = wordsList[1] as? String ?: return null
    if (!WORD_REGEX.matches(word1) || !WORD_REGEX.matches(word2)) return null
    if (word1 == word2) return null

    val icon1 = parseIcon(icon1Map) ?: return null
    val icon2 = parseIcon(icon2Map) ?: return null

    return IdentityApiResponse(
        committed = true,
        instanceId = instanceId,
        mark = JournalMark(
            icon1 = icon1,
            icon2 = icon2,
            words = listOf(word1, word2),
        ),
    )
}

fun fetchJournalIdentity(
    client: PlHttpClient,
    maxResponseBytes: Int = 64 * 1024,
): IdentityGetResult = try {
    val response = client.request(
        method = "GET",
        path = "/app/network/api/identity",
        headers = mapOf("Accept" to "application/json", "Cache-Control" to "no-cache"),
        body = null,
        maxResponseBytes = maxResponseBytes,
    )
    when (response.status) {
        200 -> {
            val parsed = parseIdentityResponse(response.bodyText())
            if (parsed != null) IdentityGetResult.Success(parsed)
            else IdentityGetResult.Failure(200, "invalid identity response JSON")
        }
        404 -> IdentityGetResult.NotFound
        else -> IdentityGetResult.Failure(response.status, response.bodyText())
    }
} catch (e: Exception) {
    IdentityGetResult.Failure(null, e.message)
}
