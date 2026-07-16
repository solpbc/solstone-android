// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import app.solstone.platform.fgs.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class PermissionRowsTest {
    @Test
    fun rendersExactRowsForBothBatteryStatesWithoutBackgroundLocation() {
        val status = PermissionStatus(
            microphoneGranted = true,
            cameraGranted = false,
            fineLocationGranted = true,
            coarseLocationGranted = false,
            backgroundLocationGranted = true,
            notificationsGranted = true,
        )

        listOf(false, true).forEach { batteryGranted ->
            val rendered = permissionRowsText(status, batteryGranted)
            val expected = listOf(
                "Microphone: true",
                "Camera: false",
                "Fine location: true",
                "Coarse location: false",
                "Notifications: true",
                "Battery exemption: $batteryGranted",
                "Permissions ready: false",
            ).joinToString("\n")
            assertContentEquals(expected.toByteArray(Charsets.UTF_8), rendered.toByteArray(Charsets.UTF_8))
            assertFalse(rendered.contains("Background" + " location"))
        }
    }
}
