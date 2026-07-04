// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class GlassesCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val action = intent.action
        val token = commandTokenFor(action, intent.getStringExtra(EXTRA_COMMAND_ACTION))
        val runtime = GlassesHarnessRuntime.runtime
            ?: (context.applicationContext as? GlassesApplication)?.runtime
        if (decideNotificationSpeak(runtime != null) == NotificationSpeakDecision.NoOp) {
            Log.w(TAG, "command ignored: ${CommandBlocked(RuntimeCommandBlockReason.RuntimeUnavailable)}")
            pending.finish()
            return
        }
        if (token == null) {
            Log.w(TAG, "command ignored: no recognized action")
            pending.finish()
            return
        }
        val commandRuntime = requireNotNull(runtime)
        val enqueued = commandRuntime.enqueueCommand {
            try {
                val result = routeCommandSurfaceCommand(
                    runtime = commandRuntime,
                    rawAction = action,
                    token = token,
                    appendDiag = GlassesDiagLog::appendRaw,
                    playCue = commandRuntime::speakCue,
                )
                if (result == null) {
                    Log.w(TAG, "command ignored: unrecognized token=$token")
                } else {
                    Log.i(TAG, "command result=$result")
                }
            } finally {
                pending.finish()
            }
        }
        if (!enqueued) {
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "GlassesCommand"
    }
}
