// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.wear.capture

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.InputStream

interface AudioCaptureDevice {
    fun prepare(onError: () -> Unit)
    fun start(): InputStream
    fun registerSilenceCallback(onSilencedChanged: (Boolean) -> Unit): Boolean
    fun unregisterSilenceCallback()
    fun stop()
    fun release()
}

class RealAudioCaptureDevice(private val context: Context) : AudioCaptureDevice {
    private var mediaRecorder: MediaRecorder? = null
    private var pipe: Array<ParcelFileDescriptor>? = null
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null

    override fun prepare(onError: () -> Unit) {
        val pipeFds = ParcelFileDescriptor.createPipe()
        pipe = pipeFds
        val mr = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
        )
        mediaRecorder = mr
        mr.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(64000)
            setOutputFile(pipeFds[1].fileDescriptor)
            setOnErrorListener { _, _, _ -> onError() }
            prepare()
        }
    }

    override fun start(): InputStream {
        checkNotNull(mediaRecorder) { "Recorder not prepared" }.start()
        val readFd = pipe?.get(0)?.fileDescriptor ?: throw IllegalStateException("Pipe not initialized")
        return FileInputStream(readFd)
    }

    override fun registerSilenceCallback(onSilencedChanged: (Boolean) -> Unit): Boolean {
        val mr = checkNotNull(mediaRecorder) { "Recorder not prepared" }
        // Registered before start: this recorder has no native port yet, so querying
        // activeRecordingConfiguration can throw. Keep input unknown until its callback.

        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                // MediaRecorder delivers only this recorder's configuration. Re-querying
                // its active configuration here can race the native start notification.
                onSilencedChanged(configs?.singleOrNull()?.isClientSilenced ?: true)
            }
        }

        mr.registerAudioRecordingCallback(context.mainExecutor, callback)
        audioRecordingCallback = callback

        return true
    }

    override fun unregisterSilenceCallback() {
        audioRecordingCallback?.let { cb ->
            try {
                mediaRecorder?.unregisterAudioRecordingCallback(cb)
            } catch (_: Exception) {}
            audioRecordingCallback = null
        }
    }

    override fun stop() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
    }

    override fun release() {
        unregisterSilenceCallback()
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        try {
            pipe?.get(0)?.close()
        } catch (_: Exception) {}
        try {
            pipe?.get(1)?.close()
        } catch (_: Exception) {}
        pipe = null
    }
}
