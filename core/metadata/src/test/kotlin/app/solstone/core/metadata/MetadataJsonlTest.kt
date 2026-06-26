// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.metadata

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class MetadataJsonlTest {
    @Test
    fun healthyLineIncludesInjectedBatteryAndZeroMotion() {
        val line = serializeLine(
            PhotoMetadataRecord(
                ts = 42L,
                battery = BatterySnapshot(level = 83, status = BatteryStatus.DISCHARGING, tempC = 31.5),
                tilt = Tilt(pitchDeg = 1.25, rollDeg = -2.5),
                motion = Motion(linAccMean = 0.0, linAccPeak = 0.0),
            ),
        )

        assertContains(line, "\"battery\":{\"level\":83,\"status\":\"discharging\",\"tempC\":31.5}")
        assertContains(line, "\"tilt\":{\"pitch\":1.25,\"roll\":-2.5}")
        assertContains(line, "\"motion\":{\"linAccMean\":0.0,\"linAccPeak\":0.0}")
    }

    @Test
    fun outageOmitsMotionAndPerFieldBatteryNulls() {
        val line = serializeLine(
            PhotoMetadataRecord(
                ts = 43L,
                battery = BatterySnapshot(level = 50, status = null, tempC = null),
                motion = null,
            ),
        )

        assertEquals("{\"ts\":43,\"battery\":{\"level\":50}}\n", line)
        assertFalse(line.contains("\"motion\""))
        assertFalse(line.contains("\"tilt\""))
        assertFalse(line.contains("\"status\""))
        assertFalse(line.contains("\"tempC\""))
    }

    @Test
    fun totalOutageLineIsMinimalAndDifferentFromHealthy() {
        val degraded = serializeLine(PhotoMetadataRecord(ts = 44L))
        val healthy = serializeLine(
            PhotoMetadataRecord(
                ts = 44L,
                battery = BatterySnapshot(level = 1, status = BatteryStatus.UNKNOWN, tempC = 20.0),
            ),
        )

        assertEquals("{\"ts\":44}\n", degraded)
        assertNotEquals(healthy, degraded)
    }
}
