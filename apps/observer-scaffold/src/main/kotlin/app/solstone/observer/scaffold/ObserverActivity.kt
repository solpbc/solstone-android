// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedDispatcher
import app.solstone.observer.formfactor.shared.ObserverHarnessUi

class ObserverActivity : Activity() {
    private lateinit var container: ObserverAppContainer
    private lateinit var spec: FormFactorSpec
    private lateinit var harnessUi: ObserverHarnessUi
    private var captureOwnerToken: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ObserverApplication
        spec = app.spec
        val runtime = ObserverHarnessRuntime.runtime ?: app.runtime.also {
            ObserverHarnessRuntime.runtime = it
        }
        container = runtime.container()
        harnessUi = ObserverHarnessUi(
            context = this,
            controller = container.controller,
            permissionRequester = { requestPermissions(spec.permissions(Build.VERSION.SDK_INT), PERMISSION_REQUEST) },
            asyncLoad = container.asyncLoad,
            previewHeightPx = spec.previewHeightPx,
            qrBackend = spec.qrBackend,
            qrThreadLabel = spec.deviceLabel.substringAfterLast(' '),
            batteryExemptionGranted = container.flavor.exemptionVerified,
            batteryGuidance = container.flavor.batteryGuidance,
            launchBatteryGuidance = container.flavor.launchBatteryGuidance,
            journalCacheState = container::journalCacheState,
            saveJournalCacheLimit = container::saveJournalCacheLimit,
            onEvidenceLoaded = { ObserverHarnessRuntime.hooks?.onEvidenceLoadComplete?.invoke() },
            onSyncLoaded = { ObserverHarnessRuntime.hooks?.onSyncLoadComplete?.invoke() },
            onJournalCacheLoadComplete = { ObserverHarnessRuntime.hooks?.onJournalCacheLoadComplete?.invoke() },
        )
        setContentView(harnessUi.view())
        if (spec.handlesPairLinks && savedInstanceState == null) {
            routePairLinkIntent(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!harnessUi.handleBack()) finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!harnessUi.handleBack()) super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        captureOwnerToken = container.captureAuthority.acquire()
        harnessUi.refreshPermissions()
    }

    override fun onStop() {
        super.onStop()
        if (!container.captureAuthority.isCurrent(captureOwnerToken)) return
        container.captureAuthority.release(captureOwnerToken)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (spec.handlesPairLinks) {
            routePairLinkIntent(intent)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            container.controller.onPermissionsRequested()
            harnessUi.refreshPermissions()
        }
    }

    private fun routePairLinkIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        harnessUi.showPairLink(uri.toString())
    }

    private companion object {
        const val PERMISSION_REQUEST = 10
    }
}
