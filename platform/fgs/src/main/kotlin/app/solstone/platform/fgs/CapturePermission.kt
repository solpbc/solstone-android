// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import android.Manifest

/**
 * The runtime permission a capture type needs — the forward direction of [PermissionStatus].
 *
 * 🔴 **This exists so a source can be asked for its OWN permission and nothing else.** Until now the
 * app requested one flat array for every declared type from whichever source screen the owner
 * happened to be on, so a grant carried no attribution: tapping `grant permissions` on the camera
 * screen produced four dialogs, and nothing downstream could tell which source the owner had
 * actually asked for.
 *
 * ⛔ **Do not hand-write a second sourceId → permission table.** Every source already declares its
 * [CaptureForegroundType], and [PermissionStatus] already has one field per type, so this is the one
 * place the two are related. A second copy would agree on the day it was written and drift after —
 * which is exactly how a deleted product name outlived its own rename in the reason table.
 */
fun capturePermission(type: CaptureForegroundType): String = when (type) {
    CaptureForegroundType.MICROPHONE -> Manifest.permission.RECORD_AUDIO
    CaptureForegroundType.CAMERA -> Manifest.permission.CAMERA
    CaptureForegroundType.LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
}

/** Whether [status] reports [type]'s permission as held. ⛔ Keep in step with [capturePermission]. */
fun capturePermissionGranted(type: CaptureForegroundType, status: PermissionStatus): Boolean =
    when (type) {
        CaptureForegroundType.MICROPHONE -> status.microphoneGranted
        CaptureForegroundType.CAMERA -> status.cameraGranted
        CaptureForegroundType.LOCATION -> status.locationGranted
    }
