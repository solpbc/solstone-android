// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import app.solstone.platform.fgs.PermissionStatus

fun permissionRowsText(status: PermissionStatus, batteryExemptionGranted: Boolean): String =
    listOf(
        "Microphone: ${status.microphoneGranted}",
        "Camera: ${status.cameraGranted}",
        "Fine location: ${status.fineLocationGranted}",
        "Coarse location: ${status.coarseLocationGranted}",
        "Notifications: ${status.notificationsGranted}",
        "Battery exemption: $batteryExemptionGranted",
        "Ready: ${status.allRequiredGranted}",
    ).joinToString("\n")
