// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class PermissionStatus(
    val microphoneGranted: Boolean,
    val notificationsGranted: Boolean,
) {
    val allRequiredGranted: Boolean
        get() = microphoneGranted && notificationsGranted
}

fun interface PermissionStatusReader {
    fun read(): PermissionStatus
}

class AndroidPermissionStatusReader(private val context: Context) : PermissionStatusReader {
    override fun read(): PermissionStatus =
        PermissionStatus(
            microphoneGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notificationsGranted = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
}
