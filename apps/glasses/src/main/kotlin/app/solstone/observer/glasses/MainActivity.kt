// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import app.solstone.observer.formfactor.glasses.GlassesHarnessUi

class MainActivity : Activity() {
    private lateinit var container: GlassesAppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = GlassesAppContainer(this)
        GlassesHarnessRuntime.container = container
        setContentView(
            GlassesHarnessUi(
                context = this,
                controller = container.controller,
                permissionRequester = { requestPermissions(requiredPermissions(), PERMISSION_REQUEST) },
                asyncLoad = container.asyncLoad,
                onEvidenceLoaded = { GlassesHarnessRuntime.hooks?.onEvidenceLoadComplete?.invoke() },
                onSyncLoaded = { GlassesHarnessRuntime.hooks?.onSyncLoadComplete?.invoke() },
            ).view(),
        )
    }

    override fun onDestroy() {
        if (isFinishing) {
            container.close()
            if (GlassesHarnessRuntime.container === container) {
                GlassesHarnessRuntime.container = null
            }
        }
        super.onDestroy()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
            )
        }

    private companion object {
        const val PERMISSION_REQUEST = 10
    }
}
