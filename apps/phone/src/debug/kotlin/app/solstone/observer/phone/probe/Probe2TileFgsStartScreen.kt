// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.probe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.solstone.platform.fgs.ObserverForegroundService
import kotlinx.coroutines.delay

internal sealed class Probe2Caller {
    abstract val token: String

    data object NoThrow : Probe2Caller() {
        override val token = "no-throw"
    }

    data object Fgsnae : Probe2Caller() {
        override val token = "fgsnae"
    }

    data object Security : Probe2Caller() {
        override val token = "security"
    }

    data class Other(val type: String) : Probe2Caller() {
        override val token = "other type=$type"
    }
}

internal object Probe2Starts {
    const val PREFS = "probe"
    const val KEY_MODE = "tile_mode"
    const val MODE_DIRECT = "direct"
    const val MODE_RELAY = "relay"

    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_DIRECT) ?: MODE_DIRECT

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode)
            .apply()
    }

    fun tryStart(context: Context): Probe2Caller {
        return try {
            context.startForegroundService(Intent(context, ObserverForegroundService::class.java))
            Probe2Caller.NoThrow
        } catch (_: SecurityException) {
            Probe2Caller.Security
        } catch (e: RuntimeException) {
            if (isFgsnae(e)) Probe2Caller.Fgsnae else Probe2Caller.Other(e.javaClass.simpleName)
        }
    }

    fun startAndClassify(context: Context, mode: String) {
        ProbeLog.install(context.applicationContext.filesDir)
        ProbeLog.acquireLifecycleDiag()
        val caller = tryStart(context)
        scheduleClassify(context, mode, caller)
    }

    fun isFgsnae(exception: RuntimeException): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            exception.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"

    fun audioBytes(context: Context): Long {
        val dir = context.cacheDir.resolve("audio-source")
        val files = dir.listFiles() ?: return 0L
        return files.filter { it.name.startsWith("audio-") && it.name.endsWith(".m4a") }.sumOf { it.length() }
    }

    fun scheduleClassify(context: Context, mode: String, caller: Probe2Caller) {
        val app = context.applicationContext
        val since = System.currentTimeMillis()
        val t0 = audioBytes(app)
        ProbeLog.appendRaw("probe=2 mode=$mode caller=${caller.token}")
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val outcome = classify(app, caller, since, t0)
                ProbeLog.appendRaw("probe=2 mode=$mode outcome=$outcome")
                updateTile(app, outcome)
            } finally {
                ProbeLog.releaseLifecycleDiag()
            }
        }, 3_000L)
    }

    fun classify(context: Context, caller: Probe2Caller, sinceEpochMs: Long, t0Bytes: Long): String {
        when (caller) {
            is Probe2Caller.Fgsnae -> return "a"
            is Probe2Caller.Security -> return "b"
            is Probe2Caller.Other -> return "other type=${caller.type}"
            is Probe2Caller.NoThrow -> Unit
        }
        val failure = ProbeLog.readAll().lineSequence().any { line ->
            val ts = line.substringAfter("ts=", "").substringBefore(' ').toLongOrNull() ?: 0L
            ts >= sinceEpochMs && "fgs start-failure exception=" in line
        }
        if (failure) return "c"
        val fresh = ObserverForegroundService.isHeartbeatFresh()
        val grew = audioBytes(context) > t0Bytes
        return when {
            fresh && !grew -> "d"
            fresh && grew -> "started-audio"
            else -> "no-heartbeat"
        }
    }

    fun subtitle(outcome: String): String = when {
        outcome == "a" -> "fgsnae"
        outcome == "b" -> "security"
        outcome == "c" -> "svc-fail"
        outcome == "d" -> "no-audio"
        outcome == "started-audio" -> "started"
        outcome.startsWith("other") -> {
            val type = outcome.substringAfter("type=", "")
            if (type.isEmpty()) "other" else "other-$type"
        }
        else -> outcome
    }

    fun updateTile(context: Context, outcome: String) {
        ProbeTileService.current()?.qsTile?.let { applySubtitle(it, outcome) }
        val component = ComponentName(context, ProbeTileService::class.java)
        TileService.requestListeningState(context, component)
    }

    fun applySubtitle(tile: Tile, outcome: String) {
        val token = subtitle(outcome)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = token
        } else {
            tile.label = "solstone FGS $token"
        }
        tile.updateTile()
    }
}

/**
 * FGS start from a quick-settings tile. Do not call startFromVisibleContext.
 * Drive: set mode on this screen, press Home so the app is fully backgrounded, tap the "solstone FGS" tile.
 *   open: adb shell am start -n app.solstone.observer.phone/app.solstone.observer.phone.probe.ProbeIndexActivity
 */
@Composable
fun Probe2TileFgsStartScreen() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(Probe2Starts.mode(context)) }
    var log by remember { mutableStateOf(ProbeLog.readAll()) }
    var heartbeatFresh by remember { mutableStateOf(ObserverForegroundService.isHeartbeatFresh()) }
    var audioBytes by remember { mutableLongStateOf(Probe2Starts.audioBytes(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            log = ProbeLog.readAll()
            heartbeatFresh = ObserverForegroundService.isHeartbeatFresh()
            audioBytes = Probe2Starts.audioBytes(context)
            delay(500)
        }
    }
    ProbeScaffold(
        measures = "caller-side startForegroundService exception class; lifecycleDiag start-failure line; isHeartbeatFresh; audio-source m4a byte growth",
        drive = "Fully background this app (Home, not Recents with this activity still visible) before tapping the tile. A visible activity satisfies the while-in-use foreground-service gate on its own, and the probe would pass for the wrong reason.",
        prediction = PROBE2_PREDICTION,
    ) {
        Text("mode=$mode")
        Button(onClick = {
            Probe2Starts.setMode(context, Probe2Starts.MODE_DIRECT)
            mode = Probe2Starts.MODE_DIRECT
        }) { Text("mode direct") }
        Button(onClick = {
            Probe2Starts.setMode(context, Probe2Starts.MODE_RELAY)
            mode = Probe2Starts.MODE_RELAY
        }) { Text("mode relay") }
        Text("isHeartbeatFresh=$heartbeatFresh")
        Text("audioBytes=$audioBytes")
        Text("no exception and capture arriving are two different readings")
        if (Build.VERSION.SDK_INT < 29) {
            Text("unavailable below API 29")
        }
        Text(log.takeLast(8000))
    }
}

private const val PROBE2_PREDICTION =
    "From a fully backgrounded app, mode 1 is predicted (a) ForegroundServiceStartNotAllowedException on API 31+. Mode 2 is predicted to start because the relay activity is visible."
