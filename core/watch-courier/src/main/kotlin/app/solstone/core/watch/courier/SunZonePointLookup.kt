// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Pure-JVM lookup mapping an IANA timezone ID to its static tzdb representative coordinate
 * `[lat100, lon100]` (hundredths of a degree) for the watch sun arc.
 *
 * Uses zero location APIs and zero permissions; consults only the bundled `sun-zone-points.json`
 * resource shared with the Wear OS watch app.
 */
object SunZonePointLookup {
    private val entryPattern = Regex(""""([^"]+)"\s*:\s*\[\s*(-?[0-9]+(?:\.[0-9]+)?)\s*,\s*(-?[0-9]+(?:\.[0-9]+)?)\s*]""")

    private val table: Map<String, IntArray> by lazy {
        val stream = SunZonePointLookup::class.java.getResourceAsStream("/sun-zone-points.json")
            ?: return@lazy emptyMap()
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val map = HashMap<String, IntArray>(512)
        for (match in entryPattern.findAll(text)) {
            val zone = match.groupValues[1]
            val lat = match.groupValues[2].toDoubleOrNull() ?: continue
            val lon = match.groupValues[3].toDoubleOrNull() ?: continue
            val lat100 = (lat * 100.0).roundToInt()
            val lon100 = (lon * 100.0).roundToInt()
            if (lat100 in -9000..9000 && lon100 in -18000..18000) {
                map[zone] = intArrayOf(lat100, lon100)
            }
        }
        map
    }

    fun lookupLatLon100(zoneId: String?): IntArray? {
        if (zoneId.isNullOrBlank()) return null
        return table[zoneId]?.copyOf()
    }

    fun systemDefaultLatLon100(): IntArray? =
        runCatching { lookupLatLon100(ZoneId.systemDefault().id) }.getOrNull()
}
