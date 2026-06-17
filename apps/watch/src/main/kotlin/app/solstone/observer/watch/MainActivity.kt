// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import app.solstone.observer.formfactor.watch.WatchHarnessUi

class MainActivity : Activity() {
    private lateinit var container: WatchAppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = WatchAppContainer(this)
        WatchHarnessRuntime.container = container
        setContentView(
            WatchHarnessUi(
                context = this,
                controller = container.controller,
                permissionRequester = { requestPermissions(requiredPermissions(), PERMISSION_REQUEST) },
            ).view(),
        )
    }

    override fun onDestroy() {
        if (isFinishing) {
            container.close()
            if (WatchHarnessRuntime.container === container) {
                WatchHarnessRuntime.container = null
            }
        }
        super.onDestroy()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    private companion object {
        const val PERMISSION_REQUEST = 10
    }
}
