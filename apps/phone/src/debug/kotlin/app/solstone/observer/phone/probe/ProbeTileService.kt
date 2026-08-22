// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.probe

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick-settings tile that starts ObserverForegroundService from a backgrounded app.
 * Drive: add the "solstone FGS" tile, fully background the app, tap the tile.
 *   open index: adb shell am start -n app.solstone.observer.phone/app.solstone.observer.phone.probe.ProbeIndexActivity
 */
class ProbeTileService : TileService() {
    override fun onCreate() {
        super.onCreate()
        ProbeLog.install(filesDir)
        ProbeLog.acquireLifecycleDiag()
    }

    override fun onDestroy() {
        ProbeLog.releaseLifecycleDiag()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        val last = ProbeLog.readAll().lineSequence()
            .mapNotNull { line ->
                val outcome = line.substringAfter("outcome=", missingDelimiterValue = "")
                    .substringBefore(' ')
                outcome.takeIf { it.isNotEmpty() && "probe=2" in line }
            }
            .lastOrNull()
        if (last != null) {
            qsTile?.let { Probe2Starts.applySubtitle(it, last) }
        }
    }

    override fun onClick() {
        super.onClick()
        val mode = Probe2Starts.mode(this)
        if (mode == Probe2Starts.MODE_RELAY) {
            startRelay()
            return
        }
        val caller = Probe2Starts.tryStart(this)
        Probe2Starts.scheduleClassify(this, mode, caller)
    }

    private fun startRelay() {
        val intent = Intent(this, ProbeTileRelayActivity::class.java)
        if (Build.VERSION.SDK_INT >= 34) {
            startRelayPending(intent)
        } else {
            startRelayLegacy(intent)
        }
    }

    @RequiresApi(34)
    private fun startRelayPending(intent: Intent) {
        val pending = PendingIntent.getActivity(
            this,
            RELAY_REQUEST,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(pending)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startRelayLegacy(intent: Intent) {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }

    private companion object {
        const val RELAY_REQUEST = 41
    }
}
