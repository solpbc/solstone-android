// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.platform.camera.still.CameraLock

fun createCaptureSetup(context: Context, cameraLock: CameraLock): CaptureSetup {
    // TODO(Lode 2): wire Camera2 StillCaptureEngine @15s + AudioContinuousSourceEngine; and make readiness not require location (PermissionStatus.allRequiredGranted hardcodes location).
    return CaptureSetup(
        engines = emptyList(),
        payloadBytesProvider = PayloadBytesProvider { error("no engines wired (Lode 1 scaffold)") },
    )
}
