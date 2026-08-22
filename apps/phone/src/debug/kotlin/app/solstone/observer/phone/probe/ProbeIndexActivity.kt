// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.probe

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Debug-only launcher for the five phone probes (compiled into debug, absent from release).
 *   open: adb shell am start -n app.solstone.observer.phone/app.solstone.observer.phone.probe.ProbeIndexActivity
 */
class ProbeIndexActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ProbeLog.install(filesDir)
        ProbeLog.acquireLifecycleDiag()
        ProbeProcess.onIndexCreated()
        setContent {
            MaterialTheme {
                ProbeIndex(onRestoreMute = { restoreMute() })
            }
        }
    }

    override fun onStop() {
        super.onStop()
        restoreMute()
    }

    override fun onDestroy() {
        ProbeLog.releaseLifecycleDiag()
        super.onDestroy()
    }

    private fun restoreMute() {
        if (!Probe1MuteRestorer.mutedByProbe) return
        getSystemService(AudioManager::class.java)?.setMicrophoneMute(false)
        Probe1MuteRestorer.mutedByProbe = false
    }
}

internal object Probe1MuteRestorer {
    @Volatile var mutedByProbe: Boolean = false
}

internal object ProbeProcess {
    val startUptimeMs: Long = android.os.Process.getStartUptimeMillis()
    private val creates = java.util.concurrent.atomic.AtomicInteger(0)

    fun onIndexCreated(): Int = creates.incrementAndGet()

    fun activityCreateCount(): Int = creates.get()
}

@Composable
private fun ProbeIndex(onRestoreMute: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    BackHandler(enabled = selected != null) {
        onRestoreMute()
        selected = null
    }
    when (selected) {
        1 -> Probe1MicrophoneMuteScreen()
        2 -> Probe2TileFgsStartScreen()
        3 -> Probe3StateDescriptionScreen()
        4 -> Probe4InsetsScreen()
        5 -> Probe5WebViewScreen()
        else -> Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("solstone probes")
            Button(onClick = { selected = 1 }) { Text("1 microphone mute") }
            Button(onClick = { selected = 2 }) { Text("2 tile FGS start") }
            Button(onClick = { selected = 3 }) { Text("3 stateDescription") }
            Button(onClick = { selected = 4 }) { Text("4 insets") }
            Button(onClick = { selected = 5 }) { Text("5 WebView") }
        }
    }
}
