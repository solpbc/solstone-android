// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.app.Activity
import android.os.Build
import android.os.Bundle
import app.solstone.observer.formfactor.shared.ObserverHarnessUi

class ObserverActivity : Activity() {
    private lateinit var container: ObserverAppContainer
    private lateinit var spec: FormFactorSpec
    private var captureOwnerToken: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ObserverApplication
        spec = app.spec
        val runtime = ObserverHarnessRuntime.runtime ?: app.runtime.also {
            ObserverHarnessRuntime.runtime = it
        }
        container = runtime.container()
        setContentView(
            ObserverHarnessUi(
                context = this,
                controller = container.controller,
                permissionRequester = { requestPermissions(spec.permissions(Build.VERSION.SDK_INT), PERMISSION_REQUEST) },
                asyncLoad = container.asyncLoad,
                previewHeightPx = spec.previewHeightPx,
                qrBackend = spec.qrBackend,
                qrThreadLabel = spec.deviceLabel.substringAfterLast(' '),
                onEvidenceLoaded = { ObserverHarnessRuntime.hooks?.onEvidenceLoadComplete?.invoke() },
                onSyncLoaded = { ObserverHarnessRuntime.hooks?.onSyncLoadComplete?.invoke() },
            ).view(),
        )
    }

    override fun onResume() {
        super.onResume()
        captureOwnerToken = container.captureAuthority.acquire()
    }

    override fun onStop() {
        super.onStop()
        if (!container.captureAuthority.isCurrent(captureOwnerToken)) return
        container.captureAuthority.release(captureOwnerToken)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private companion object {
        const val PERMISSION_REQUEST = 10
    }
}
