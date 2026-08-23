// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.camera.camera2

import android.hardware.camera2.CameraCharacteristics

internal fun chooseRearCameraId(ids: List<String>, facingOf: (String) -> Int?): String? {
    return ids.firstOrNull { id ->
        facingOf(id) == CameraCharacteristics.LENS_FACING_BACK
    }
}
