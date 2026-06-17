// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import app.solstone.observer.formfactor.phone.PhoneHarnessUi

class MainActivity : Activity() {
    private lateinit var container: PhoneAppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = PhoneAppContainer(this)
        PhoneHarnessRuntime.container = container
        setContentView(
            PhoneHarnessUi(
                context = this,
                controller = container.controller,
                permissionRequester = { requestPermissions(requiredPermissions(), PERMISSION_REQUEST) },
                asyncLoad = container.asyncLoad,
                onEvidenceLoaded = { PhoneHarnessRuntime.hooks?.onEvidenceLoadComplete?.invoke() },
                onSyncLoaded = { PhoneHarnessRuntime.hooks?.onSyncLoadComplete?.invoke() },
            ).view(),
        )
    }

    override fun onDestroy() {
        if (isFinishing) {
            container.close()
            if (PhoneHarnessRuntime.container === container) {
                PhoneHarnessRuntime.container = null
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
