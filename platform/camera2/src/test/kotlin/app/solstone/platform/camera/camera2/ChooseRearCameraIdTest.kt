// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.camera.camera2

import android.hardware.camera2.CameraCharacteristics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChooseRearCameraIdTest {
    @Test
    fun chooseRearCameraIdReturnsNullForFrontOnlyIds() {
        val result = chooseRearCameraId(listOf("0", "1")) { CameraCharacteristics.LENS_FACING_FRONT }

        assertNull(result)
    }

    @Test
    fun chooseRearCameraIdReturnsNullWhenEveryFacingIsNull() {
        val result = chooseRearCameraId(listOf("0", "1")) { null }

        assertNull(result)
    }

    @Test
    fun chooseRearCameraIdReturnsFirstBackId() {
        val result = chooseRearCameraId(listOf("0", "1")) { id ->
            when (id) {
                "0" -> CameraCharacteristics.LENS_FACING_FRONT
                "1" -> CameraCharacteristics.LENS_FACING_BACK
                else -> null
            }
        }

        assertEquals("1", result)
    }

    @Test
    fun chooseRearCameraIdReturnsNullForExternalOnlyIds() {
        val result = chooseRearCameraId(listOf("0")) { CameraCharacteristics.LENS_FACING_EXTERNAL }

        assertNull(result)
    }
}
