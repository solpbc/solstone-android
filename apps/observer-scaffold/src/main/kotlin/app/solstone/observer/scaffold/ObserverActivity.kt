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
            journalCacheState = container::journalCacheState,
            saveJournalCacheLimit = container::saveJournalCacheLimit,
            onEvidenceLoaded = { ObserverHarnessRuntime.hooks?.onEvidenceLoadComplete?.invoke() },
            onSyncLoaded = { ObserverHarnessRuntime.hooks?.onSyncLoadComplete?.invoke() },
            onJournalCacheLoadComplete = { ObserverHarnessRuntime.hooks?.onJournalCacheLoadComplete?.invoke() },
        )
        // ⛔ Decide single-task mode BEFORE the first screen is built: the harness reads it when it
        // lays out a screen, and its menu lists operator instrumentation an owner must not reach by
        // backing out of the one task the shell sent them here for.
        if (isOwnerTask(intent, firstLaunch = savedInstanceState == null)) {
            harnessUi.dismissTo(::finish)
        }
        setContentView(harnessUi.view())
        if (!routeDirectIntent(intent) && spec.handlesPairLinks && savedInstanceState == null) {
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
        // ⚠ Same reason as the phone shell: a permission the owner allowed in system Settings has
        // no in-app callback, and returning here is the event.
        container.sources.onPermissionStatus(container.controller.refreshPermissions())
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
        // `singleTop` on the phone, so a second App Link re-enters here rather than through
        // onCreate — the same single-task decision has to be made again.
        if (isOwnerTask(intent, firstLaunch = true)) {
            harnessUi.dismissTo(::finish)
        }
        if (!routeDirectIntent(intent) && spec.handlesPairLinks) {
            routePairLinkIntent(intent)
        }
    }

    private fun isOwnerTask(intent: Intent?, firstLaunch: Boolean): Boolean {
        val target = intent ?: return false
        return isOwnerTaskLaunch(
            scansPairQr = target.getBooleanExtra(EXTRA_SCAN_PAIR_QR, false),
            showsLocalCache = target.getBooleanExtra(EXTRA_SHOW_LOCAL_CACHE, false),
            isViewAction = target.action == Intent.ACTION_VIEW,
            hasData = target.data != null,
            handlesPairLinks = spec.handlesPairLinks,
            firstLaunch = firstLaunch,
        )
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

    private fun routeDirectIntent(intent: Intent): Boolean = when {
        intent.getBooleanExtra(EXTRA_SCAN_PAIR_QR, false) -> {
            harnessUi.showScanPairQr()
            true
        }
        intent.getBooleanExtra(EXTRA_SHOW_LOCAL_CACHE, false) -> {
            harnessUi.showLocalCache()
            true
        }
        else -> false
    }

    companion object {
        const val EXTRA_SCAN_PAIR_QR = "app.solstone.observer.scaffold.EXTRA_SCAN_PAIR_QR"
        const val EXTRA_SHOW_LOCAL_CACHE = "app.solstone.observer.scaffold.EXTRA_SHOW_LOCAL_CACHE"
        private const val PERMISSION_REQUEST = 10
    }
}
