// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

data class WatchLocationFix(
    val provider: String,
    val timestampEpochMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Double = Double.NaN,
    val fixAgeMs: Long? = null,
)

object WatchLocationRecord {
    fun buildLocationRecord(fix: WatchLocationFix): String =
        buildLocationRecord(listOf(fix))

    fun buildLocationRecord(fixes: List<WatchLocationFix>): String {
        if (fixes.isEmpty()) return ""
        return buildString {
            append("{\"schema\":\"solstone.location.segment/1\",\"platform\":\"wearos\"}\n")
            for (fix in fixes) {
                append('{')
                append("\"provider\":\"").append(escapeJson(fix.provider)).append('"')
                append(",\"timestamp\":").append(fix.timestampEpochMs)
                append(",\"lat\":").append(fix.lat)
                append(",\"lon\":").append(fix.lon)
                if (!fix.accuracyMeters.isNaN()) {
                    append(",\"accuracy\":").append(fix.accuracyMeters)
                }
                if (fix.fixAgeMs != null) {
                    append(",\"fixAge\":").append(fix.fixAgeMs)
                }
                append("}\n")
            }
        }
    }

    private fun escapeJson(value: String): String = buildString {
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
}
