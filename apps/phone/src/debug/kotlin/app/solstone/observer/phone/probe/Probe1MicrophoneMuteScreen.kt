// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.SensorPrivacyManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Mute / privacy signals while capture is running.
 * Drive: adb shell cmd sensor_privacy enable|disable <user> <sensor>
 * Fill-in: user 0, sensor 1 (SensorPrivacyManager.Sensors.MICROPHONE).
 */
@Composable
fun Probe1MicrophoneMuteScreen() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var mute by remember { mutableStateOf(audio?.isMicrophoneMute ?: false) }
    var broadcastFired by remember { mutableStateOf(false) }
    var broadcastAt by remember { mutableLongStateOf(0L) }
    var recordings by remember { mutableStateOf<List<AudioRecordingConfiguration>>(emptyList()) }
    var recordingsAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var muteAt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun readMute(): Boolean {
        val value = audio?.isMicrophoneMute ?: false
        if (value != mute) muteAt = System.currentTimeMillis()
        mute = value
        return value
    }

    fun readRecordings() {
        if (audio == null) return
        recordings = audio.activeRecordingConfigurations
        recordingsAt = System.currentTimeMillis()
    }

    DisposableEffect(audio) {
        if (audio == null) return@DisposableEffect onDispose { }
        readRecordings()
        val handler = Handler(Looper.getMainLooper())
        val recordingCb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                recordings = configs.toList()
                recordingsAt = System.currentTimeMillis()
            }
        }
        audio.registerAudioRecordingCallback(recordingCb, handler)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                broadcastFired = true
                broadcastAt = System.currentTimeMillis()
                val nowMute = readMute()
                ProbeLog.appendRaw("probe=1 broadcastFired=true isMicrophoneMute=$nowMute")
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val filter = IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        }
        onDispose {
            audio.unregisterAudioRecordingCallback(recordingCb)
            if (Build.VERSION.SDK_INT >= 28) {
                runCatching { context.unregisterReceiver(receiver) }
            }
            if (Probe1MuteRestorer.mutedByProbe) {
                audio.setMicrophoneMute(false)
                Probe1MuteRestorer.mutedByProbe = false
            }
        }
    }

    ProbeScaffold(
        measures = "isMicrophoneMute, ACTION_MICROPHONE_MUTE_CHANGED, device-wide isClientSilenced rows, supportsSensorToggle(MICROPHONE)",
        drive = "adb shell cmd sensor_privacy enable|disable <user> <sensor>\nuser 0, sensor 1 = MICROPHONE",
        prediction = PROBE1_PREDICTION,
        gaps = PROBE1_GAPS,
    ) {
        Text("isMicrophoneMute=$mute changedAt=$muteAt")
        Text(
            if (Build.VERSION.SDK_INT >= 28) {
                "broadcastFired=$broadcastFired at=$broadcastAt"
            } else {
                "unavailable below API 28"
            },
        )
        Text(sensorPrivacyLine(context))
        Text("recordings=${recordings.size} at=$recordingsAt")
        if (recordings.isEmpty()) {
            Text("recordings=0")
        } else {
            recordings.forEachIndexed { index, config ->
                Text(recordingLine(index, config))
            }
        }
        Button(onClick = {
            audio?.setMicrophoneMute(true)
            Probe1MuteRestorer.mutedByProbe = true
            val nowMute = readMute()
            ProbeLog.appendRaw("probe=1 broadcastFired=false isMicrophoneMute=$nowMute")
        }) { Text("setMicrophoneMute true") }
        Button(onClick = {
            audio?.setMicrophoneMute(false)
            Probe1MuteRestorer.mutedByProbe = false
            val nowMute = readMute()
            ProbeLog.appendRaw("probe=1 broadcastFired=false isMicrophoneMute=$nowMute")
        }) { Text("setMicrophoneMute false") }
        Text(
            "If this process dies while the in-app mute control is on, the device microphone stays muted.",
        )
        Text(ProbeLog.readAll().takeLast(8000))
    }
}

private fun recordingLine(index: Int, config: AudioRecordingConfiguration): String {
    val device = config.audioDevice
    val deviceText = if (device == null) {
        "device=null"
    } else {
        "type=${device.type} id=${device.id}"
    }
    val silenced = if (Build.VERSION.SDK_INT >= 29) {
        "isClientSilenced=${config.isClientSilenced}"
    } else {
        "unavailable below API 29"
    }
    return "row=$index session=${config.clientAudioSessionId} source=${config.clientAudioSource} $deviceText $silenced"
}

private fun sensorPrivacyLine(context: Context): String {
    if (Build.VERSION.SDK_INT < 31) return "supportsSensorToggle=unavailable below API 31"
    val manager = context.getSystemService(SensorPrivacyManager::class.java) ?: return "supportsSensorToggle=null"
    val sensor = SensorPrivacyManager.Sensors.MICROPHONE
    val supports = manager.supportsSensorToggle(sensor)
    return "supportsSensorToggle=$supports"
}

private const val PROBE1_PREDICTION =
    "isMicrophoneMute() under a toggle-only mute: false — INVERSION\n" +
        "runtime-registered ACTION_MICROPHONE_MUTE_CHANGED: fires\n" +
        "isClientSilenced(): true — INVERSION\n" +
        "supportsSensorToggle(MICROPHONE): device-dependent — says whether the toggle exists here\n" +
        "Broadcast + getter is a broken detector by construction: the intent javadoc says to call isMicrophoneMute() on arrival, and under a toggle-only mute that getter is predicted false while the broadcast still fires."

private const val PROBE1_GAPS =
    "solstone records through MediaRecorder behind start() / finish() / discard(), and the recorder never reaches this process, so this screen cannot mark our own row in the device-wide list below. The fix is not matching — MediaRecorder.getActiveRecordingConfiguration() is public and returns this recorder's own configuration directly; exposing it through platform/audio is a one-method change.\n\n" +
        "Client port id is hidden on the public SDK. The platform matches a process to its own row by that id, which is why the device-wide list below cannot be correlated. This screen does not reflect into hidden APIs."
