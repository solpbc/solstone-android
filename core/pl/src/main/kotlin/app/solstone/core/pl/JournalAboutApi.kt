// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

sealed interface JournalAboutResult {
    data class Accepted(val about: JournalAbout) : JournalAboutResult
    data object NotFound : JournalAboutResult
    data object Failed : JournalAboutResult
}

data class JournalAbout(
    val version: String,
    val build: String?,
    val os: String,
    val osVersion: String,
    val arch: String,
)

fun decodeAbout(bodyText: String): JournalAbout? = runCatching {
    val root = parseJson(bodyText) as? Map<*, *> ?: return null
    if (exactJsonInteger(root["protocol_version"]) != 1L) return null
    val version = root["version"] as? String ?: return null
    if (version.isEmpty()) return null
    val os = root["os"] as? String ?: return null
    val osVersion = root["os_version"] as? String ?: return null
    val arch = root["arch"] as? String ?: return null
    val about = root["about"] as? String ?: return null
    if (about.isEmpty()) return null
    val build = when (val raw = root["build"]) {
        null -> null
        is String -> raw.takeIf(String::isNotEmpty) ?: return null
        else -> return null
    }
    val storedFacts = listOfNotNull(version, build, os, osVersion, arch)
    if (storedFacts.any { fact -> fact.any { it == '\t' || it == '\r' || it == '\n' } }) return null
    val line = renderLine("journal", version, build, os, osVersion, arch)
    if (!line.matches(Regex("^journal [^\\r\\n]+$")) || " · last seen " in line) return null
    JournalAbout(version = version, build = build, os = os, osVersion = osVersion, arch = arch)
}.getOrNull()

fun fetchJournalAbout(client: PlHttpClient): JournalAboutResult = try {
    val response = client.request(
        method = "GET",
        path = "/api/system/about",
        headers = mapOf("Accept" to "application/json", "Cache-Control" to "no-cache"),
        body = null,
        maxResponseBytes = 64 * 1024,
    )
    when (response.status) {
        200 -> decodeAbout(response.bodyText())?.let(JournalAboutResult::Accepted) ?: JournalAboutResult.Failed
        404 -> JournalAboutResult.NotFound
        else -> JournalAboutResult.Failed
    }
} catch (_: Exception) {
    JournalAboutResult.Failed
}
