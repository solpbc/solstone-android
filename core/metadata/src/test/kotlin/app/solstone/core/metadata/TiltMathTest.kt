// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.metadata

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TiltMathTest {
    @Test
    fun derivesPitchAndRollFromQuaternion() {
        val half = sqrt(0.5).toFloat()
        val tilt = tiltFrom(RotationVectorSample(x = half, y = 0f, z = 0f, w = half, timestampEpochMs = 1_000L))

        assertEquals(0.0, tilt.pitchDeg, absoluteTolerance = 0.000001)
        assertEquals(90.0, tilt.rollDeg, absoluteTolerance = 0.00001)
    }

    @Test
    fun reconstructsMissingWAndSelectsNearestSample() {
        val half = sqrt(0.5).toFloat()
        val tilt = nearestTilt(
            photoTs = 1_100L,
            samples = listOf(
                RotationVectorSample(x = half, y = 0f, z = 0f, w = null, timestampEpochMs = 1_000L),
                RotationVectorSample(x = 0f, y = half, z = 0f, w = half, timestampEpochMs = 1_095L),
            ),
        )

        assertEquals(90.0, tilt?.pitchDeg ?: error("tilt missing"), absoluteTolerance = 0.02)
        assertEquals(0.0, tilt.rollDeg, absoluteTolerance = 0.000001)
    }

    @Test
    fun zeroRotationSamplesOmitTilt() {
        assertNull(nearestTilt(photoTs = 1_000L, samples = emptyList()))
    }
}
