// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import app.solstone.observer.formfactor.shared.ObserverHarnessUi

// ComponentActivity, not android.app.Activity: it installs the view-tree lifecycle and
// saved-state owners on setContentView. The phone's pairing mark is a ComposeView, and a
// ComposeView attached under a plain Activity throws "ViewTreeLifecycleOwner not found"
// the moment a pair succeeds.
class ObserverActivity : ComponentActivity() {
    private lateinit var container: ObserverAppContainer
    private lateinit var spec: FormFactorSpec
    private lateinit var harnessUi: ObserverHarnessUi
    private var captureOwnerToken: Long = -1L
    private var cameraAsk = CameraAsk.NotAsked

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraAsk = savedInstanceState?.getString(STATE_CAMERA_ASK)
            ?.let { saved -> CameraAsk.entries.firstOrNull { it.name == saved } }
            ?: CameraAsk.NotAsked
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
            onEvidenceLoaded = { ObserverHarnessRuntime.hooks?.onEvidenceLoadComplete?.invoke() },
            onSyncLoaded = { ObserverHarnessRuntime.hooks?.onSyncLoadComplete?.invoke() },
            markAccessoryFactory = spec.pairingAccessoryFactory,
            ownerButtonStyle = spec.ownerButtonStyle,
            ownerTextStyle = spec.ownerTextStyle,
        )
        // ⛔ Decide single-task mode BEFORE the first screen is built: the harness reads it when it
        // lays out a screen, and its menu lists operator instrumentation an owner must not reach by
        // backing out of the one task the shell sent them here for.
        if (isOwnerTask(intent, firstLaunch = savedInstanceState == null)) {
            harnessUi.dismissTo(::finish)
        }
        // The harness menu's own `Scan pair QR` entry takes the same camera gate the shell's
        // does. Without this it called straight into the preview and opened the camera first.
        harnessUi.scanEntry = ::enterScan
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
        container.onOwnerResumed()
        harnessUi.refreshPermissions()
        // The owner took the camera-off screen's route through system Settings and came back.
        if (harnessUi.showsCameraOff && hasCamera()) harnessUi.showScanPairQr()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CAMERA_ASK, cameraAsk.name)
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
        // A new tap from the shell is a new entry and may ask again, but never over a prompt that
        // is still up.
        if (cameraAsk != CameraAsk.Awaiting) cameraAsk = CameraAsk.NotAsked
        if (!routeDirectIntent(intent) && spec.handlesPairLinks) {
            routePairLinkIntent(intent)
        }
    }

    private fun isOwnerTask(intent: Intent?, firstLaunch: Boolean): Boolean {
        val target = intent ?: return false
        return isOwnerTaskLaunch(
            scansPairQr = target.getBooleanExtra(EXTRA_SCAN_PAIR_QR, false),
            isViewAction = target.action == Intent.ACTION_VIEW,
            hasData = target.data != null,
            handlesPairLinks = spec.handlesPairLinks,
            firstLaunch = firstLaunch,
        )
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            container.controller.onPermissionsRequested()
            harnessUi.refreshPermissions()
        }
        if (requestCode == CAMERA_FOR_SCAN_REQUEST) {
            // ⚠ Read the grant itself, not `grantResults`: an interrupted request answers with an
            // empty array, and that has to land somewhere too.
            cameraAsk = CameraAsk.Answered
            container.controller.onPermissionsRequested()
            enterScan()
        }
    }

    private fun hasCamera(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun enterScan() {
        when (scanEntry(hasCamera(), cameraAsk)) {
            ScanEntry.Scan -> harnessUi.showScanPairQr()
            ScanEntry.AskForCamera -> {
                cameraAsk = CameraAsk.Awaiting
                // ⛔ Before the ask: allowing the camera to scan a code must not read as the owner
                // turning the camera source on.
                container.onScannerNeedsCamera()
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_FOR_SCAN_REQUEST)
            }
            ScanEntry.AwaitAnswer -> Unit
            // Same guard on the way to system Settings, where the grant has no callback at all.
            ScanEntry.CameraOff -> harnessUi.showCameraOff(beforeSettings = container::onScannerNeedsCamera)
        }
    }

    private fun routePairLinkIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        harnessUi.showPairLink(uri.toString())
    }

    private fun routeDirectIntent(intent: Intent): Boolean = when {
        intent.getBooleanExtra(EXTRA_SCAN_PAIR_QR, false) -> {
            enterScan()
            true
        }
        else -> false
    }

    companion object {
        const val EXTRA_SCAN_PAIR_QR = "app.solstone.observer.scaffold.EXTRA_SCAN_PAIR_QR"
        private const val PERMISSION_REQUEST = 10
        private const val CAMERA_FOR_SCAN_REQUEST = 11
        private const val STATE_CAMERA_ASK = "app.solstone.observer.scaffold.STATE_CAMERA_ASK"
    }
}
