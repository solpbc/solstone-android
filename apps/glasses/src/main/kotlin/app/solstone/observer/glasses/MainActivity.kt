// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import app.solstone.core.diagnostics.DiagEvent
import app.solstone.observer.formfactor.glasses.GlassesHarnessUi

class MainActivity : Activity() {
    private lateinit var container: GlassesAppContainer
    private var captureOwnerToken: Long = -1L
    private var captureStartedForOwner: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GlassesHarnessRuntime.runtime ?: (application as GlassesApplication).runtime.also {
            GlassesHarnessRuntime.runtime = it
        }
        container = runtime.container()
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

    override fun onResume() {
        super.onResume()
        captureOwnerToken = container.captureAuthority.acquire()
        captureStartedForOwner = false
        GlassesDiagLog.emit(DiagEvent.CaptureOwner(DiagEvent.CaptureOwnerTransition.RESUMED_ACQUIRED))
        startIfEligible()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        val ownerToken = captureOwnerToken
        if (!container.captureAuthority.isCurrent(ownerToken)) return
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        GlassesDiagLog.emit(DiagEvent.CaptureOwner(DiagEvent.CaptureOwnerTransition.SCREEN_ON_CLEARED))
        container.teardownVisibleOwner(ownerToken) {
            captureStartedForOwner = false
            GlassesDiagLog.emit(DiagEvent.CaptureOwner(DiagEvent.CaptureOwnerTransition.STOPPED_RELEASED))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && container.captureAuthority.isCurrent(captureOwnerToken)) {
            startIfEligible()
        }
    }

    // Swipe forward/back arrive as VOLUME_UP/VOLUME_DOWN; we consume ONLY those so the harness UI keeps
    // DPAD_CENTER/ENTER for its own activation (the Phase-1a key-hijack regression fix).
    // AC5 caveat: consuming here does not stop system/MediaSession-level volume routing to the paired
    // BT phone; full suppression needs a registered MediaSession/VolumeProvider (noted follow-up, not done).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(TAG, "temple KeyEvent keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")
        val action = swipeAction(keyCode) ?: return super.onKeyDown(keyCode, event)
        container.handleSwipe(action, keyCode)
        return true
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_IMAGES,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            )
        }

    private fun startIfEligible() {
        if (captureStartedForOwner) return
        container.startVisibleAsync { started ->
            if (!started || captureStartedForOwner || !container.captureAuthority.isCurrent(captureOwnerToken)) return@startVisibleAsync
            captureStartedForOwner = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            GlassesDiagLog.emit(DiagEvent.CaptureOwner(DiagEvent.CaptureOwnerTransition.SCREEN_ON_SET))
            GlassesDiagLog.emit(DiagEvent.CaptureOwner(DiagEvent.CaptureOwnerTransition.START_ACCEPTED))
        }
    }

    private companion object {
        const val TAG = "GlassesStatus"
        const val PERMISSION_REQUEST = 10
    }
}
