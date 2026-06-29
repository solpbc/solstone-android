// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import app.solstone.observer.formfactor.glasses.GlassesHarnessUi

class MainActivity : Activity() {
    private lateinit var container: GlassesAppContainer

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
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // Swipe forward/back arrive as VOLUME_UP/VOLUME_DOWN; we consume ONLY those so the harness UI keeps
    // DPAD_CENTER/ENTER for its own activation (the Phase-1a key-hijack regression fix).
    // AC5 caveat: consuming here does not stop system/MediaSession-level volume routing to the paired
    // BT phone; full suppression needs a registered MediaSession/VolumeProvider (noted follow-up, not done).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(TAG, "temple KeyEvent keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")
        val action = swipeAction(keyCode, container.controller.desiredOn)
            ?: return super.onKeyDown(keyCode, event)
        dispatchSwipe(
            action,
            start = container.controller::start,
            stop = container.controller::stop,
            announce = container::speakCurrentStatus,
            attention = container::speakNeedsAttention,
        )
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

    private companion object {
        const val TAG = "GlassesStatus"
        const val PERMISSION_REQUEST = 10
    }
}
