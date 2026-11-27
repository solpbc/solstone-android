// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.garmin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.URI

/**
 * Pure read-only probe for Garmin Connect Mobile availability and companion link URL matching.
 * Never invokes Garmin SDK APIs and is only consulted for an already-activated Garmin source.
 */
object GarminEnvironmentProbe {
    const val GCM_PACKAGE_NAME = "com.garmin.android.apps.connectmobile"
    private const val MIN_GCM_VERSION_CODE = 10617L

    enum class ConnectAppStatus {
        NOT_INSTALLED,
        UPGRADE_NEEDED,
        READY,
    }

    fun garminConnectStatus(context: Context): ConnectAppStatus {
        val pm = context.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(GCM_PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(GCM_PACKAGE_NAME, 0)
            }
        }.getOrNull() ?: return ConnectAppStatus.NOT_INSTALLED

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return if (versionCode < MIN_GCM_VERSION_CODE) {
            ConnectAppStatus.UPGRADE_NEEDED
        } else {
            ConnectAppStatus.READY
        }
    }

    fun isGarminActivationLink(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.rawAuthority == "go.solstone.app" &&
            (uri.rawPath == "/garmin" || uri.rawPath == "/garmin/")
    }
}
