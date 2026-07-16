// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QrPreviewTransformTest {
    @Test
    fun phoneShapeProducesUniformFitAtZeroAndNinetyDegrees() {
        assertUniformEffectiveScale(1080, 480, 640, 480, 0)
        assertUniformEffectiveScale(1080, 480, 640, 480, 90)

        val transform = qrPreviewTransform(1080, 480, 640, 480, 0)
        assertEquals(0.5925926f, transform.scaleX, TOLERANCE)
        assertEquals(1f, transform.scaleY, TOLERANCE)
        assertEquals(540f, transform.pivotX, TOLERANCE)
        assertEquals(240f, transform.pivotY, TOLERANCE)
    }

    @Test
    fun tallRotatedBufferProducesUniformFit() {
        assertUniformEffectiveScale(720, 480, 480, 640, 90)
    }

    @Test
    fun matchingAspectIsNoOp() {
        val transform = qrPreviewTransform(960, 720, 640, 480, 180)

        assertEquals(1f, transform.scaleX, TOLERANCE)
        assertEquals(1f, transform.scaleY, TOLERANCE)
        assertEquals(480f, transform.pivotX, TOLERANCE)
        assertEquals(360f, transform.pivotY, TOLERANCE)
    }

    @Test
    fun degenerateDimensionsReturnIdentityAtOrigin() {
        listOf(
            qrPreviewTransform(0, 480, 640, 480, 0),
            qrPreviewTransform(1080, 0, 640, 480, 0),
            qrPreviewTransform(1080, 480, 0, 480, 0),
            qrPreviewTransform(1080, 480, 640, -1, 0),
        ).forEach { transform ->
            assertEquals(QrPreviewTransform(1f, 1f, 0f, 0f), transform)
        }
    }

    @Test
    fun mismatchedAspectIsCorrectedAwayFromIdentity() {
        val transform = qrPreviewTransform(1080, 480, 640, 480, 0)

        assertNotEquals(QrPreviewTransform(1f, 1f, 0f, 0f), transform)
        assertNotEquals(transform.scaleX, transform.scaleY, TOLERANCE)
    }

    private fun assertUniformEffectiveScale(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
    ) {
        val transform = qrPreviewTransform(viewWidth, viewHeight, bufferWidth, bufferHeight, rotationDegrees)
        val swap = Math.floorMod(rotationDegrees, 180) == 90
        val rotatedWidth = if (swap) bufferHeight else bufferWidth
        val rotatedHeight = if (swap) bufferWidth else bufferHeight
        val effectiveX = viewWidth.toFloat() / rotatedWidth * transform.scaleX
        val effectiveY = viewHeight.toFloat() / rotatedHeight * transform.scaleY

        assertEquals(effectiveX, effectiveY, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
