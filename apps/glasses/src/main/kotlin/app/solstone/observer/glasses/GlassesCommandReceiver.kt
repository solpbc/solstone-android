// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

class GlassesCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val token = intent.getStringExtra(EXTRA_COMMAND_ACTION) ?: rokidButtonActionToken(intent.action)
        executor.execute {
            try {
                val runtime = GlassesHarnessRuntime.runtime
                    ?: (context.applicationContext as? GlassesApplication)?.runtime
                if (decideNotificationSpeak(runtime != null) == NotificationSpeakDecision.NoOp) {
                    Log.w(TAG, "command ignored: ${CommandBlocked(RuntimeCommandBlockReason.RuntimeUnavailable)}")
                    return@execute
                }
                if (token == null) {
                    Log.w(TAG, "command ignored: no recognized action")
                    return@execute
                }
                val result = routeDebugRuntimeCommand(requireNotNull(runtime), null, token)
                if (result == null) {
                    Log.w(TAG, "command ignored: unrecognized token=$token")
                } else {
                    Log.i(TAG, "command result=$result")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "GlassesCommand"
        val executor = Executors.newSingleThreadExecutor()
    }
}
