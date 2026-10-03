// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

fun normalizeArch(arch: String): String = when (arch) {
    "aarch64", "ARM64", "arm64-v8a", "arm64" -> "arm64"
    "amd64", "x64", "AMD64", "x86_64" -> "x86_64"
    else -> arch
}

fun renderLine(
    name: String,
    version: String,
    build: String?,
    os: String,
    osVersion: String,
    arch: String,
): String = buildString {
    val strippedVersion = version.trimStart { it == 'v' }
    append(name)
    if (strippedVersion.isNotEmpty()) append(" ").append(strippedVersion)
    if (!build.isNullOrEmpty()) append(" (").append(build).append(')')
    if (os.isNotEmpty()) {
        append(" · ").append(os)
        if (osVersion.isNotEmpty()) append(' ').append(osVersion)
    }
    if (arch.isNotEmpty()) append(" · ").append(normalizeArch(arch))
}

@Suppress("UNUSED_PARAMETER")
fun nativeArchForAbout(machineObservation: String?, processOrCompiledAbi: String? = null): String? {
    return machineObservation?.takeIf(String::isNotBlank)?.let(::normalizeArch)
}

fun freshnessSuffix(freshness: JournalVersionFreshness, versionSeenAt: Long?, now: Long): String {
    if (freshness != JournalVersionFreshness.LAST_KNOWN || versionSeenAt == null) return ""
    val age = now - versionSeenAt
    if (age < 0L) return ""
    val phrase = when {
        age < 90_000L -> "just now"
        age < 3_600_000L -> {
            val minutes = age / 60_000L
            if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        }
        age < 86_400_000L -> {
            val hours = age / 3_600_000L
            if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }
        else -> {
            val days = age / 86_400_000L
            if (days == 1L) "1 day ago" else "$days days ago"
        }
    }
    return " · last seen $phrase"
}

fun journalDisplayLine(reading: JournalVersionReading?, now: Long): String {
    if (reading == null || reading.freshness == JournalVersionFreshness.NEVER_OBSERVED || reading.version.isNullOrBlank()) {
        return "journal unknown"
    }
    return renderLine(
        name = "journal",
        version = reading.version,
        build = reading.build,
        os = reading.os.orEmpty(),
        osVersion = reading.osVersion.orEmpty(),
        arch = reading.arch.orEmpty(),
    ) + freshnessSuffix(reading.freshness, reading.versionSeenAt, now)
}

fun aboutBlock(appLine: String, journalLine: String): String = "$appLine\n$journalLine"
