// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.i18n.phoneText

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
): String {
    val strippedVersion = version.trimStart { it == 'v' }
    val named = if (strippedVersion.isEmpty()) name else phoneText("android.about.line.nameversion", "name" to name, "version" to strippedVersion)
    val built = if (build.isNullOrEmpty()) "" else phoneText("android.about.line.build.exact", "build" to build)
    val system = if (os.isEmpty()) "" else if (osVersion.isEmpty()) phoneText("android.about.line.os.exact", "os" to os)
        else phoneText("android.about.line.osversion.exact", "os" to os, "version" to osVersion)
    val architecture = if (arch.isEmpty()) "" else phoneText("android.about.line.arch.exact", "arch" to normalizeArch(arch))
    return phoneText("android.about.line.whole", "named" to named, "built" to built, "system" to system, "architecture" to architecture)
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
        age < 90_000L -> phoneText("android.about.freshnessnow")
        age < 3_600_000L -> {
            val minutes = age / 60_000L
            phoneText("android.about.freshnessminutes", "count" to (minutes))
        }
        age < 86_400_000L -> {
            val hours = age / 3_600_000L
            phoneText("android.about.freshnesshours", "count" to (hours))
        }
        else -> {
            val days = age / 86_400_000L
            phoneText("android.about.freshnessdays", "count" to (days))
        }
    }
    return " · " + phoneText("android.about.last.seen.phrase", "relative" to (phrase))
}

fun journalDisplayLine(reading: JournalVersionReading?, now: Long): String {
    if (reading == null || reading.freshness == JournalVersionFreshness.NEVER_OBSERVED || reading.version.isNullOrBlank()) {
        return phoneText("android.about.journal.unknown")
    }
    return renderLine(
        name = phoneText("android.about.journal.name"),
        version = reading.version,
        build = reading.build,
        os = reading.os.orEmpty(),
        osVersion = reading.osVersion.orEmpty(),
        arch = reading.arch.orEmpty(),
    ) + freshnessSuffix(reading.freshness, reading.versionSeenAt, now)
}

fun aboutBlock(appLine: String, journalLine: String): String = phoneText("android.about.appline.journalline" , "appline" to (appLine), "journalline" to (journalLine))
