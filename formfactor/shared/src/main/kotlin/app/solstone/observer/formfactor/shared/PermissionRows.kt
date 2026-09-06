// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.satisfiableCaptureForegroundTypes

fun permissionRowsText(
    status: PermissionStatus,
    declared: Set<CaptureForegroundType> = setOf(
        CaptureForegroundType.MICROPHONE,
        CaptureForegroundType.LOCATION,
        CaptureForegroundType.CAMERA,
    ),
): String {
    val ready = satisfiableCaptureForegroundTypes(
        microphoneGranted = status.microphoneGranted,
        cameraGranted = status.cameraGranted,
        locationGranted = status.locationGranted,
        declared = declared,
    ).isNotEmpty()
    return listOf(
        "Microphone: ${status.microphoneGranted}",
        "Camera: ${status.cameraGranted}",
        "Location: ${status.locationGranted}",
        "Notifications: ${status.notificationsGranted}",
        "Permissions ready: $ready",
    ).joinToString("\n")
}
