// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.location

import app.solstone.core.model.GapEvent

data class LocationFix(
    val provider: String,
    val timestampEpochMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Double,
    val fixAgeMs: Long,
)

enum class NoFixReason(val detail: String) {
    NO_FIX("no_fix"),
    PERMISSION("permission"),
    PROVIDER_DISABLED("provider_disabled"),
}

fun buildLocationRecord(fix: LocationFix): String =
    buildString {
        append('{')
        append("\"provider\":\"").append(escapeJson(fix.provider)).append('"')
        append(",\"timestamp\":").append(fix.timestampEpochMs)
        append(",\"lat\":").append(fix.lat)
        append(",\"lon\":").append(fix.lon)
        append(",\"accuracy\":").append(fix.accuracyMeters)
        append(",\"fixAge\":").append(fix.fixAgeMs)
        append("}\n")
    }

fun decideGap(reason: NoFixReason, atEpochMs: Long): GapEvent =
    GapEvent("location_gap", atEpochMs, reason.detail)

private fun escapeJson(value: String): String =
    buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
