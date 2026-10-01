// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrAnalysisSizeTest {
    // A Galaxy A36 back camera's YUV sizes up to 1920, plus its full-sensor size.
    private val a36Sizes = listOf(
        176 to 144, 320 to 240, 240 to 320, 352 to 288, 640 to 360, 640 to 400, 640 to 480,
        720 to 480, 800 to 480, 864 to 480, 800 to 600, 1024 to 738, 1024 to 768, 1280 to 720,
        1280 to 768, 1080 to 1080, 1280 to 960, 1440 to 1080, 1600 to 1200, 1920 to 1080,
        1920 to 1440, 4080 to 3060,
    )

    @Test
    fun picksTheSmallestReadableSizeInTheSensorsShapeNotTheSmallestOffered() {
        assertEquals(1024 to 768, qrAnalysisSize(a36Sizes))
    }

    @Test
    fun aSixteenByNineSensorGetsASixteenByNineFrame() {
        assertEquals(1280 to 720, qrAnalysisSize(listOf(176 to 144, 1024 to 768, 1280 to 720, 3840 to 2160)))
    }

    @Test
    fun withoutTheSensorsShapeTakesAnyReadableSize() {
        assertEquals(1280 to 720, qrAnalysisSize(listOf(176 to 144, 1280 to 720, 1920 to 1080, 4032 to 3024)))
    }

    @Test
    fun withNothingLargeEnoughTakesTheLargestUnderTheCap() {
        assertEquals(640 to 480, qrAnalysisSize(listOf(176 to 144, 640 to 480, 4032 to 3024)))
    }

    @Test
    fun withOnlyOversizedFramesTakesTheSmallestOffered() {
        assertEquals(3264 to 2448, qrAnalysisSize(listOf(4032 to 3024, 3264 to 2448)))
    }

    @Test
    fun noSizesIsNoChoice() {
        assertNull(qrAnalysisSize(emptyList()))
    }
}
