// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

/**
 * Debug-only adb entry point (compiled into the debug build only, absent from release).
 *   pair:    adb shell am broadcast -n app.solstone.observer.glasses/.DebugPairLinkReceiver -e pair_link <url>
 *   observe: adb shell am broadcast -n app.solstone.observer.glasses/.DebugPairLinkReceiver -e action observe_start
 *   stop:    adb shell am broadcast -n app.solstone.observer.glasses/.DebugPairLinkReceiver -e action observe_stop
 *   sync:    adb shell am broadcast -n app.solstone.observer.glasses/.DebugPairLinkReceiver -e action sync_now
 * Mirrors the temple-swipe gestures (start/stop) + manual sync for on-device validation
 * where injected volume keys never reach MainActivity.onKeyDown.
 */
class DebugPairLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val pairLink = intent.getStringExtra(EXTRA_PAIR_LINK)
        val action = intent.getStringExtra(EXTRA_ACTION)
        executor.execute {
            try {
                val runtime = GlassesHarnessRuntime.runtime
                    ?: (context.applicationContext as? GlassesApplication)?.runtime
                if (runtime == null) {
                    Log.w(TAG, "debug command result=${CommandBlocked(RuntimeCommandBlockReason.RuntimeUnavailable)}")
                    return@execute
                }
                val result = when {
                    !pairLink.isNullOrBlank() -> runtime.pairLink(pairLink)
                    action == "observe_start" -> runtime.observeStart()
                    action == "observe_stop" -> runtime.observeStop()
                    action == "sync_now" -> runtime.syncNow()
                    else -> {
                        Log.w(TAG, "no recognized extra (pair_link / action)")
                        null
                    }
                }
                if (result != null) {
                    Log.i(TAG, "debug command result=$result")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "GlassesDebug"
        const val EXTRA_PAIR_LINK = "pair_link"
        const val EXTRA_ACTION = "action"
        val executor = Executors.newSingleThreadExecutor()
    }
}
