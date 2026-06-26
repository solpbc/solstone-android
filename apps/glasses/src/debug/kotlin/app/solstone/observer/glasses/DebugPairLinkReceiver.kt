// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/**
 * Debug-only adb entry point:
 * adb shell am broadcast -n app.solstone.observer.glasses/.DebugPairLinkReceiver -e pair_link '<url>'
 */
class DebugPairLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val pairLink = intent.getStringExtra(EXTRA_PAIR_LINK)
        executor.execute {
            try {
                if (!pairLink.isNullOrBlank()) {
                    GlassesHarnessRuntime.container?.controller?.onScannedPairLink(pairLink)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val EXTRA_PAIR_LINK = "pair_link"
        val executor = Executors.newSingleThreadExecutor()
    }
}
