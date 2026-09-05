// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

fun sanitizeJournalVersion(raw: String): String? {
    if (raw.any { it in '\u0000'..'\u001F' || it == '\u007F' }) return null
    return raw.trim().ifBlank { null }
}

fun parseJournalVersionCurrent(bodyText: String): String? {
    val root = runCatching { parseJson(bodyText) as? Map<*, *> }.getOrNull() ?: return null
    val versionMap = root["version"] as? Map<*, *> ?: return null
    val current = versionMap["current"] as? String ?: return null
    return sanitizeJournalVersion(current)
}

fun fetchJournalVersion(client: PlHttpClient): String? =
    try {
        val response = client.request("GET", "/api/system/status", mapOf("Cache-Control" to "no-cache"), null)
        if (response.status == 200) {
            parseJournalVersionCurrent(response.bodyText())
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
