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
        instance = this
        ProbeLog.install(filesDir)
        ProbeLog.acquireLifecycleDiag()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        ProbeLog.releaseLifecycleDiag()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        instance = this
        val last = ProbeLog.readAll().lineSequence()
            .mapNotNull { line ->
                if ("probe=2" !in line || "outcome=" !in line) return@mapNotNull null
                line.substringAfter("outcome=").trim().takeIf { it.isNotEmpty() }
            }
            .lastOrNull()
        if (last != null) {
            qsTile?.let { Probe2Starts.applySubtitle(it, last) }
        }
    }

    override fun onStopListening() {
        if (instance === this) instance = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val mode = Probe2Starts.mode(this)
        if (mode == Probe2Starts.MODE_RELAY) {
            startRelay()
            return
        }
        Probe2Starts.startAndClassify(this, mode)
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

    companion object {
        private const val RELAY_REQUEST = 41

        @Volatile private var instance: ProbeTileService? = null

        fun current(): ProbeTileService? = instance
    }
}
