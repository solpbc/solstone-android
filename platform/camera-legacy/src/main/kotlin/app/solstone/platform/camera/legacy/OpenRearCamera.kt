// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
@file:Suppress("DEPRECATION")

package app.solstone.platform.camera.legacy

import android.hardware.Camera

internal fun <T : Any> openRearCamera(
    cameraCount: Int,
    facingAt: (Int) -> Int?,
    open: (Int) -> T,
): T? {
    for (index in 0 until cameraCount) {
        if (facingAt(index) == Camera.CameraInfo.CAMERA_FACING_BACK) {
            return open(index)
        }
    }
    return null
}
