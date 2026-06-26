// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.metadata

data class PhotoMetadataRecord(
    val ts: Long,
    val battery: BatterySnapshot? = null,
    val tilt: Tilt? = null,
    val motion: Motion? = null,
)

fun serializeLine(record: PhotoMetadataRecord): String =
    buildString {
        append('{')
        append("\"ts\":").append(record.ts)
        appendBattery(record.battery)
        record.tilt?.let { tilt ->
            append(",\"tilt\":{")
            append("\"pitch\":").append(tilt.pitchDeg)
            append(",\"roll\":").append(tilt.rollDeg)
            append('}')
        }
        record.motion?.let { motion ->
            append(",\"motion\":{")
            append("\"linAccMean\":").append(motion.linAccMean)
            append(",\"linAccPeak\":").append(motion.linAccPeak)
            append('}')
        }
        append("}\n")
    }

private fun StringBuilder.appendBattery(battery: BatterySnapshot?) {
    if (battery == null) return
    val fields = mutableListOf<String>()
    battery.level?.let { fields += "\"level\":$it" }
    battery.status?.let { fields += "\"status\":\"${escapeJson(it.wireValue)}\"" }
    battery.tempC?.let { fields += "\"tempC\":$it" }
    if (fields.isEmpty()) return
    append(",\"battery\":{")
    append(fields.joinToString(","))
    append('}')
}

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
