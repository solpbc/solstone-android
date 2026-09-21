// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanPairReticleGeometryTest {

    private val density = 2.625f

    @Test
    fun standardPhoneShapeBindsToCap() {
        val widthPx = 411f * density
        val heightPx = 891f * density
        val geometry = scanPairReticleGeometry(widthPx, heightPx, density)

        assertEquals(682.5f, geometry.sidePx, TOLERANCE)
        assertEquals(10.5f, geometry.strokePx, TOLERANCE)
        assertEquals(163.8f, geometry.armPx, TOLERANCE)

        assertPolylinesInwardMirrored(geometry, widthPx, heightPx)
    }

    @Test
    fun narrowPhoneShapeBindsToWidth() {
        val widthPx = 320f * density
        val heightPx = 480f * density
        val geometry = scanPairReticleGeometry(widthPx, heightPx, density)

        assertEquals(537.6f, geometry.sidePx, TOLERANCE)
        assertEquals(10.5f, geometry.strokePx, TOLERANCE)

        assertPolylinesInwardMirrored(geometry, widthPx, heightPx)
    }

    @Test
    fun shortLandscapeShapeBindsToHeight() {
        val widthPx = 800f * density
        val heightPx = 360f * density
        val geometry = scanPairReticleGeometry(widthPx, heightPx, density)

        assertEquals(604.8f, geometry.sidePx, TOLERANCE)
        assertEquals(145.152f, geometry.armPx, TOLERANCE)
        assertEquals(10.5f, geometry.strokePx, TOLERANCE)

        assertPolylinesInwardMirrored(geometry, widthPx, heightPx)
    }

    @Test
    fun degenerateDimensionsReturnEmptyGeometry() {
        listOf(
            scanPairReticleGeometry(0f, 480f, density),
            scanPairReticleGeometry(320f, 0f, density),
            scanPairReticleGeometry(320f, 480f, 0f),
            scanPairReticleGeometry(-10f, 480f, density),
        ).forEach { geometry ->
            assertEquals(0f, geometry.sidePx, TOLERANCE)
            assertEquals(0f, geometry.armPx, TOLERANCE)
            assertEquals(0f, geometry.strokePx, TOLERANCE)
            assertTrue(geometry.topLeft.isEmpty())
            assertTrue(geometry.topRight.isEmpty())
            assertTrue(geometry.bottomLeft.isEmpty())
            assertTrue(geometry.bottomRight.isEmpty())
        }
    }

    private fun assertPolylinesInwardMirrored(
        geometry: ScanPairReticleGeometry,
        widthPx: Float,
        heightPx: Float,
    ) {
        val side = geometry.sidePx
        val arm = geometry.armPx
        val left = (widthPx - side) / 2f
        val top = (heightPx - side) / 2f
        val right = left + side
        val bottom = top + side

        // Top-left: (left, top+arm) -> (left, top) -> (left+arm, top)
        assertEquals(3, geometry.topLeft.size)
        assertEquals(left, geometry.topLeft[0].x, TOLERANCE)
        assertEquals(top + arm, geometry.topLeft[0].y, TOLERANCE)
        assertEquals(left, geometry.topLeft[1].x, TOLERANCE)
        assertEquals(top, geometry.topLeft[1].y, TOLERANCE)
        assertEquals(left + arm, geometry.topLeft[2].x, TOLERANCE)
        assertEquals(top, geometry.topLeft[2].y, TOLERANCE)

        // Top-right: (right-arm, top) -> (right, top) -> (right, top+arm)
        assertEquals(3, geometry.topRight.size)
        assertEquals(right - arm, geometry.topRight[0].x, TOLERANCE)
        assertEquals(top, geometry.topRight[0].y, TOLERANCE)
        assertEquals(right, geometry.topRight[1].x, TOLERANCE)
        assertEquals(top, geometry.topRight[1].y, TOLERANCE)
        assertEquals(right, geometry.topRight[2].x, TOLERANCE)
        assertEquals(top + arm, geometry.topRight[2].y, TOLERANCE)

        // Bottom-left: (left, bottom-arm) -> (left, bottom) -> (left+arm, bottom)
        assertEquals(3, geometry.bottomLeft.size)
        assertEquals(left, geometry.bottomLeft[0].x, TOLERANCE)
        assertEquals(bottom - arm, geometry.bottomLeft[0].y, TOLERANCE)
        assertEquals(left, geometry.bottomLeft[1].x, TOLERANCE)
        assertEquals(bottom, geometry.bottomLeft[1].y, TOLERANCE)
        assertEquals(left + arm, geometry.bottomLeft[2].x, TOLERANCE)
        assertEquals(bottom, geometry.bottomLeft[2].y, TOLERANCE)

        // Bottom-right: (right-arm, bottom) -> (right, bottom) -> (right, bottom-arm)
        assertEquals(3, geometry.bottomRight.size)
        assertEquals(right - arm, geometry.bottomRight[0].x, TOLERANCE)
        assertEquals(bottom, geometry.bottomRight[0].y, TOLERANCE)
        assertEquals(right, geometry.bottomRight[1].x, TOLERANCE)
        assertEquals(bottom, geometry.bottomRight[1].y, TOLERANCE)
        assertEquals(right, geometry.bottomRight[2].x, TOLERANCE)
        assertEquals(bottom - arm, geometry.bottomRight[2].y, TOLERANCE)
    }

    private companion object {
        private const val TOLERANCE = 0.0001f
    }
}
