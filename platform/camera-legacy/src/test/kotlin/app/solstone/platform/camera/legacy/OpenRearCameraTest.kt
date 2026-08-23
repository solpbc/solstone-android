// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
@file:Suppress("DEPRECATION")

package app.solstone.platform.camera.legacy

import android.hardware.Camera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class OpenRearCameraTest {
    private data class FakeHandle(val index: Int)

    private val unknownFacing =
        Camera.CameraInfo.CAMERA_FACING_FRONT + Camera.CameraInfo.CAMERA_FACING_BACK + 1

    @Test
    fun openRearCameraOpensFirstBackIndexAndDoesNotOpenFront() {
        val opened = mutableListOf<Int>()
        val handles = mapOf(
            0 to FakeHandle(0),
            1 to FakeHandle(1),
        )

        val result = openRearCamera(
            cameraCount = 2,
            facingAt = { index ->
                when (index) {
                    0 -> Camera.CameraInfo.CAMERA_FACING_FRONT
                    1 -> Camera.CameraInfo.CAMERA_FACING_BACK
                    else -> null
                }
            },
            open = { index ->
                opened += index
                handles.getValue(index)
            },
        )

        assertSame(handles.getValue(1), result)
        assertEquals(listOf(1), opened)
    }

    @Test
    fun openRearCameraReturnsNullWithoutOpeningWhenNoBackFacing() {
        val opened = mutableListOf<Int>()

        val result = openRearCamera(
            cameraCount = 1,
            facingAt = { Camera.CameraInfo.CAMERA_FACING_FRONT },
            open = { index ->
                opened += index
                FakeHandle(index)
            },
        )

        assertNull(result)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun openRearCameraReturnsNullWithoutOpeningWhenCameraCountIsZero() {
        val opened = mutableListOf<Int>()

        val result = openRearCamera(
            cameraCount = 0,
            facingAt = { Camera.CameraInfo.CAMERA_FACING_BACK },
            open = { index ->
                opened += index
                FakeHandle(index)
            },
        )

        assertNull(result)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun openRearCameraReturnsNullWithoutOpeningWhenEveryFacingIsUnreadable() {
        val opened = mutableListOf<Int>()

        val result = openRearCamera(
            cameraCount = 2,
            facingAt = { null },
            open = { index ->
                opened += index
                FakeHandle(index)
            },
        )

        assertNull(result)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun openRearCameraSkipsUnknownFacingAndOpensLaterBack() {
        assertNotEquals(Camera.CameraInfo.CAMERA_FACING_BACK, unknownFacing)
        assertNotEquals(Camera.CameraInfo.CAMERA_FACING_FRONT, unknownFacing)

        val opened = mutableListOf<Int>()
        val handles = mapOf(
            0 to FakeHandle(0),
            1 to FakeHandle(1),
        )

        val result = openRearCamera(
            cameraCount = 2,
            facingAt = { index ->
                when (index) {
                    0 -> unknownFacing
                    1 -> Camera.CameraInfo.CAMERA_FACING_BACK
                    else -> null
                }
            },
            open = { index ->
                opened += index
                handles.getValue(index)
            },
        )

        assertSame(handles.getValue(1), result)
        assertEquals(listOf(1), opened)
    }
}
