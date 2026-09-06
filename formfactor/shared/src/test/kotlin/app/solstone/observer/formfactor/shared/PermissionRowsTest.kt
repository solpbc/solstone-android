// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import app.solstone.platform.fgs.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class PermissionRowsTest {
    @Test
    fun rendersExactRowsWithoutBackgroundLocation() {
        val status = PermissionStatus(
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = true,
            notificationsGranted = true,
        )

        val rendered = permissionRowsText(status)
        val expected = listOf(
            "Microphone: true",
            "Camera: false",
            "Location: true",
            "Notifications: true",
            "Permissions ready: true",
        ).joinToString("\n")
        assertContentEquals(expected.toByteArray(Charsets.UTF_8), rendered.toByteArray(Charsets.UTF_8))
        assertFalse(rendered.contains("Background" + " location"))
    }

    @Test
    fun rendersNotReadyWhenNoCapturePermissionsGranted() {
        val status = PermissionStatus(
            microphoneGranted = false,
            cameraGranted = false,
            locationGranted = false,
            notificationsGranted = true,
        )

        val rendered = permissionRowsText(status)
        val expected = listOf(
            "Microphone: false",
            "Camera: false",
            "Location: false",
            "Notifications: true",
            "Permissions ready: false",
        ).joinToString("\n")
        assertContentEquals(expected.toByteArray(Charsets.UTF_8), rendered.toByteArray(Charsets.UTF_8))
    }
}
