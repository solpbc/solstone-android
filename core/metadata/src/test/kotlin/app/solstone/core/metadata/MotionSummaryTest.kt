// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MotionSummaryTest {
    @Test
    fun computesLinearAccelerationMeanAndPeak() {
        val motion = motionFrom(
            listOf(
                LinearAccelerationSample(3f, 4f, 0f, 1_000L),
                LinearAccelerationSample(0f, 0f, 12f, 1_010L),
            ),
        )

        assertEquals(8.5, motion?.linAccMean)
        assertEquals(12.0, motion?.linAccPeak)
    }

    @Test
    fun zeroSamplesOmitMotionButAllZeroSamplesArePresent() {
        assertNull(motionFrom(emptyList()))

        val motion = motionFrom(
            listOf(
                LinearAccelerationSample(0f, 0f, 0f, 1_000L),
                LinearAccelerationSample(0f, 0f, 0f, 1_010L),
            ),
        )

        assertEquals(0.0, motion?.linAccMean)
        assertEquals(0.0, motion?.linAccPeak)
    }
}
